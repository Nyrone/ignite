/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.portable;

import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.internal.processors.cache.CacheObjectImpl;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.marshaller.MarshallerExclusions;
import org.apache.ignite.marshaller.optimized.OptimizedMarshaller;
import org.apache.ignite.marshaller.portable.PortableMarshaller;
import org.jetbrains.annotations.Nullable;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import org.apache.ignite.binary.BinaryObjectException;
import org.apache.ignite.binary.BinaryIdMapper;
import org.apache.ignite.binary.Binarylizable;
import org.apache.ignite.binary.BinarySerializer;

import static java.lang.reflect.Modifier.isStatic;
import static java.lang.reflect.Modifier.isTransient;

/**
 * Portable class descriptor.
 */
public class PortableClassDescriptor {
    /** */
    private final PortableContext ctx;

    /** */
    private final Class<?> cls;

    /** */
    private final BinarySerializer serializer;

    /** ID mapper. */
    private final BinaryIdMapper idMapper;

    /** */
    private final BinaryWriteMode mode;

    /** */
    private final boolean userType;

    /** */
    private final int typeId;

    /** */
    private final String typeName;

    /** Affinity key field name. */
    private final String affKeyFieldName;

    /** */
    private final Constructor<?> ctor;

    /** */
    private final BinaryFieldAccessor[] fields;

    /** */
    private final Method writeReplaceMtd;

    /** */
    private final Method readResolveMtd;

    /** */
    private final Map<String, Integer> stableFieldsMeta;

    /** Object schemas. Initialized only for serializable classes and contains only 1 entry. */
    private final PortableSchema stableSchema;

    /** Schema registry. */
    private final PortableSchemaRegistry schemaReg;

    /** */
    private final boolean keepDeserialized;

    /** */
    private final boolean registered;

    /** */
    private final boolean useOptMarshaller;

    /** */
    private final boolean excluded;

    /**
     * @param ctx Context.
     * @param cls Class.
     * @param userType User type flag.
     * @param typeId Type ID.
     * @param typeName Type name.
     * @param affKeyFieldName Affinity key field name.
     * @param idMapper ID mapper.
     * @param serializer Serializer.
     * @param metaDataEnabled Metadata enabled flag.
     * @param keepDeserialized Keep deserialized flag.
     * @param registered Whether typeId has been successfully registered by MarshallerContext or not.
     * @param predefined Whether the class is predefined or not.
     * @throws BinaryObjectException In case of error.
     */
    PortableClassDescriptor(
        PortableContext ctx,
        Class<?> cls,
        boolean userType,
        int typeId,
        String typeName,
        @Nullable String affKeyFieldName,
        @Nullable BinaryIdMapper idMapper,
        @Nullable BinarySerializer serializer,
        boolean metaDataEnabled,
        boolean keepDeserialized,
        boolean registered,
        boolean predefined
    ) throws BinaryObjectException {
        assert ctx != null;
        assert cls != null;
        assert idMapper != null;

        this.ctx = ctx;
        this.cls = cls;
        this.typeId = typeId;
        this.userType = userType;
        this.typeName = typeName;
        this.affKeyFieldName = affKeyFieldName;
        this.serializer = serializer;
        this.idMapper = idMapper;
        this.keepDeserialized = keepDeserialized;
        this.registered = registered;

        schemaReg = ctx.schemaRegistry(typeId);

        excluded = MarshallerExclusions.isExcluded(cls);

        useOptMarshaller = !predefined && initUseOptimizedMarshallerFlag();

        if (excluded)
            mode = BinaryWriteMode.EXCLUSION;
        else
            mode = serializer != null ? BinaryWriteMode.PORTABLE : PortableUtils.mode(cls);

        switch (mode) {
            case BYTE:
            case SHORT:
            case INT:
            case LONG:
            case FLOAT:
            case DOUBLE:
            case CHAR:
            case BOOLEAN:
            case DECIMAL:
            case STRING:
            case UUID:
            case DATE:
            case TIMESTAMP:
            case BYTE_ARR:
            case SHORT_ARR:
            case INT_ARR:
            case LONG_ARR:
            case FLOAT_ARR:
            case DOUBLE_ARR:
            case CHAR_ARR:
            case BOOLEAN_ARR:
            case DECIMAL_ARR:
            case STRING_ARR:
            case UUID_ARR:
            case DATE_ARR:
            case TIMESTAMP_ARR:
            case OBJECT_ARR:
            case COL:
            case MAP:
            case MAP_ENTRY:
            case PORTABLE_OBJ:
            case ENUM:
            case ENUM_ARR:
            case CLASS:
            case EXCLUSION:
                ctor = null;
                fields = null;
                stableFieldsMeta = null;
                stableSchema = null;

                break;

            case PORTABLE:
            case EXTERNALIZABLE:
                ctor = constructor(cls);
                fields = null;
                stableFieldsMeta = null;
                stableSchema = null;

                break;

            case OBJECT:
                ctor = constructor(cls);
                ArrayList<BinaryFieldAccessor> fields0 = new ArrayList<>();
                stableFieldsMeta = metaDataEnabled ? new HashMap<String, Integer>() : null;

                PortableSchema.Builder schemaBuilder = PortableSchema.Builder.newBuilder();

                Collection<String> names = new HashSet<>();
                Collection<Integer> ids = new HashSet<>();

                for (Class<?> c = cls; c != null && !c.equals(Object.class); c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        int mod = f.getModifiers();

                        if (!isStatic(mod) && !isTransient(mod)) {
                            f.setAccessible(true);

                            String name = f.getName();

                            if (!names.add(name))
                                throw new BinaryObjectException("Duplicate field name: " + name);

                            int fieldId = idMapper.fieldId(typeId, name);

                            if (!ids.add(fieldId))
                                throw new BinaryObjectException("Duplicate field ID: " + name);

                            BinaryFieldAccessor fieldInfo = BinaryFieldAccessor.create(f, fieldId);

                            fields0.add(fieldInfo);

                            schemaBuilder.addField(fieldId);

                            if (metaDataEnabled)
                                stableFieldsMeta.put(name, fieldInfo.mode().typeId());
                        }
                    }
                }
                
                fields = fields0.toArray(new BinaryFieldAccessor[fields0.size()]);
                
                stableSchema = schemaBuilder.build();
                
                break;

            default:
                // Should never happen.
                throw new BinaryObjectException("Invalid mode: " + mode);
        }

        if (mode == BinaryWriteMode.PORTABLE || mode == BinaryWriteMode.EXTERNALIZABLE ||
            mode == BinaryWriteMode.OBJECT) {
            readResolveMtd = U.findNonPublicMethod(cls, "readResolve");
            writeReplaceMtd = U.findNonPublicMethod(cls, "writeReplace");
        }
        else {
            readResolveMtd = null;
            writeReplaceMtd = null;
        }
    }

    /**
     * @return Described class.
     */
    Class<?> describedClass() {
        return cls;
    }

    /**
     * @return Type ID.
     */
    public int typeId() {
        return typeId;
    }

    /**
     * @return User type flag.
     */
    public boolean userType() {
        return userType;
    }

    /**
     * @return Fields meta data.
     */
    Map<String, Integer> fieldsMeta() {
        return stableFieldsMeta;
    }

    /**
     * @return Schema.
     */
    PortableSchema schema() {
        return stableSchema;
    }

    /**
     * @return Keep deserialized flag.
     */
    boolean keepDeserialized() {
        return keepDeserialized;
    }

    /**
     * @return Whether typeId has been successfully registered by MarshallerContext or not.
     */
    public boolean registered() {
        return registered;
    }

    /**
     * @return {@code true} if {@link OptimizedMarshaller} must be used instead of {@link PortableMarshaller}
     * for object serialization and deserialization.
     */
    public boolean useOptimizedMarshaller() {
        return useOptMarshaller;
    }

    /**
     * Checks whether the class values are explicitly excluded from marshalling.
     *
     * @return {@code true} if excluded, {@code false} otherwise.
     */
    public boolean excluded() {
        return excluded;
    }

    /**
     * Get ID mapper.
     *
     * @return ID mapper.
     */
    public BinaryIdMapper idMapper() {
        return idMapper;
    }

    /**
     * @return portableWriteReplace() method
     */
    @Nullable Method getWriteReplaceMethod() {
        return writeReplaceMtd;
    }

    /**
     * @return portableReadResolve() method
     */
    @SuppressWarnings("UnusedDeclaration")
    @Nullable Method getReadResolveMethod() {
        return readResolveMtd;
    }

    /**
     * @param obj Object.
     * @param writer Writer.
     * @throws BinaryObjectException In case of error.
     */
    void write(Object obj, BinaryWriterExImpl writer) throws BinaryObjectException {
        assert obj != null;
        assert writer != null;

        writer.typeId(typeId);

        switch (mode) {
            case BYTE:
                writer.writeByteFieldPrimitive((byte) obj);

                break;

            case SHORT:
                writer.writeShortFieldPrimitive((short)obj);

                break;

            case INT:
                writer.writeIntFieldPrimitive((int) obj);

                break;

            case LONG:
                writer.writeLongFieldPrimitive((long) obj);

                break;

            case FLOAT:
                writer.writeFloatFieldPrimitive((float) obj);

                break;

            case DOUBLE:
                writer.writeDoubleFieldPrimitive((double) obj);

                break;

            case CHAR:
                writer.writeCharFieldPrimitive((char) obj);

                break;

            case BOOLEAN:
                writer.writeBooleanFieldPrimitive((boolean) obj);

                break;

            case DECIMAL:
                writer.doWriteDecimal((BigDecimal)obj);

                break;

            case STRING:
                writer.doWriteString((String)obj);

                break;

            case UUID:
                writer.doWriteUuid((UUID)obj);

                break;

            case DATE:
                writer.doWriteDate((Date)obj);

                break;

            case TIMESTAMP:
                writer.doWriteTimestamp((Timestamp)obj);

                break;

            case BYTE_ARR:
                writer.doWriteByteArray((byte[])obj);

                break;

            case SHORT_ARR:
                writer.doWriteShortArray((short[]) obj);

                break;

            case INT_ARR:
                writer.doWriteIntArray((int[]) obj);

                break;

            case LONG_ARR:
                writer.doWriteLongArray((long[]) obj);

                break;

            case FLOAT_ARR:
                writer.doWriteFloatArray((float[]) obj);

                break;

            case DOUBLE_ARR:
                writer.doWriteDoubleArray((double[]) obj);

                break;

            case CHAR_ARR:
                writer.doWriteCharArray((char[]) obj);

                break;

            case BOOLEAN_ARR:
                writer.doWriteBooleanArray((boolean[]) obj);

                break;

            case DECIMAL_ARR:
                writer.doWriteDecimalArray((BigDecimal[]) obj);

                break;

            case STRING_ARR:
                writer.doWriteStringArray((String[]) obj);

                break;

            case UUID_ARR:
                writer.doWriteUuidArray((UUID[]) obj);

                break;

            case DATE_ARR:
                writer.doWriteDateArray((Date[]) obj);

                break;

            case TIMESTAMP_ARR:
                writer.doWriteTimestampArray((Timestamp[]) obj);

                break;

            case OBJECT_ARR:
                writer.doWriteObjectArray((Object[])obj);

                break;

            case COL:
                writer.doWriteCollection((Collection<?>)obj);

                break;

            case MAP:
                writer.doWriteMap((Map<?, ?>)obj);

                break;

            case MAP_ENTRY:
                writer.doWriteMapEntry((Map.Entry<?, ?>)obj);

                break;

            case ENUM:
                writer.doWriteEnum((Enum<?>)obj);

                break;

            case ENUM_ARR:
                writer.doWriteEnumArray((Object[])obj);

                break;

            case CLASS:
                writer.doWriteClass((Class)obj);

                break;

            case PORTABLE_OBJ:
                writer.doWritePortableObject((BinaryObjectImpl)obj);

                break;

            case PORTABLE:
                if (writeHeader(obj, writer)) {
                    try {
                        if (serializer != null)
                            serializer.writeBinary(obj, writer);
                        else
                            ((Binarylizable)obj).writeBinary(writer);

                        writer.postWrite(userType);

                        // Check whether we need to update metadata.
                        if (obj.getClass() != BinaryMetadata.class) {
                            int schemaId = writer.schemaId();

                            if (schemaReg.schema(schemaId) == null) {
                                // This is new schema, let's update metadata.
                                BinaryMetadataCollector collector =
                                    new BinaryMetadataCollector(typeId, typeName, idMapper);

                                if (serializer != null)
                                    serializer.writeBinary(obj, collector);
                                else
                                    ((Binarylizable)obj).writeBinary(collector);

                                PortableSchema newSchema = collector.schema();

                                BinaryMetadata meta = new BinaryMetadata(typeId, typeName, collector.meta(),
                                    affKeyFieldName, Collections.singleton(newSchema));

                                ctx.updateMetadata(typeId, meta);

                                schemaReg.addSchema(newSchema.schemaId(), newSchema);
                            }
                        }
                    }
                    finally {
                        writer.popSchema();
                    }
                }

                break;

            case EXTERNALIZABLE:
                if (writeHeader(obj, writer)) {
                    writer.rawWriter();

                    try {
                        ((Externalizable)obj).writeExternal(writer);

                        writer.postWrite(userType);
                    }
                    catch (IOException e) {
                        throw new BinaryObjectException("Failed to write Externalizable object: " + obj, e);
                    }
                    finally {
                        writer.popSchema();
                    }
                }

                break;

            case OBJECT:
                if (writeHeader(obj, writer)) {
                    try {
                        for (BinaryFieldAccessor info : fields)
                            info.write(obj, writer);

                        writer.schemaId(stableSchema.schemaId());

                        writer.postWrite(userType);
                    }
                    finally {
                        writer.popSchema();
                    }
                }

                break;

            default:
                assert false : "Invalid mode: " + mode;
        }
    }

    /**
     * @param reader Reader.
     * @return Object.
     * @throws BinaryObjectException If failed.
     */
    Object read(BinaryReaderExImpl reader) throws BinaryObjectException {
        assert reader != null;

        Object res;

        switch (mode) {
            case PORTABLE:
                res = newInstance();

                reader.setHandler(res);

                if (serializer != null)
                    serializer.readBinary(res, reader);
                else
                    ((Binarylizable)res).readBinary(reader);

                break;

            case EXTERNALIZABLE:
                res = newInstance();

                reader.setHandler(res);

                try {
                    ((Externalizable)res).readExternal(reader);
                }
                catch (IOException | ClassNotFoundException e) {
                    throw new BinaryObjectException("Failed to read Externalizable object: " +
                        res.getClass().getName(), e);
                }

                break;

            case OBJECT:
                res = newInstance();

                reader.setHandler(res);

                for (BinaryFieldAccessor info : fields)
                    info.read(res, reader);

                break;

            default:
                assert false : "Invalid mode: " + mode;

                return null;
        }

        if (readResolveMtd != null) {
            try {
                res = readResolveMtd.invoke(res);

                reader.setHandler(res);
            }
            catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
            catch (InvocationTargetException e) {
                if (e.getTargetException() instanceof BinaryObjectException)
                    throw (BinaryObjectException)e.getTargetException();

                throw new BinaryObjectException("Failed to execute readResolve() method on " + res, e);
            }
        }

        return res;
    }

    /**
     * @param obj Object.
     * @param writer Writer.
     * @return Whether further write is needed.
     */
    private boolean writeHeader(Object obj, BinaryWriterExImpl writer) {
        if (writer.tryWriteAsHandle(obj))
            return false;

        if (registered) {
            PortableUtils.writeHeader(
                writer,
                typeId,
                obj instanceof CacheObjectImpl ? 0 : obj.hashCode(),
                null
            );
        }
        else {
            PortableUtils.writeHeader(
                writer,
                GridPortableMarshaller.UNREGISTERED_TYPE_ID,
                obj instanceof CacheObjectImpl ? 0 : obj.hashCode(),
                cls.getName()
            );
        }

        return true;
    }

    /**
     * @return Instance.
     * @throws BinaryObjectException In case of error.
     */
    private Object newInstance() throws BinaryObjectException {
        assert ctor != null;

        try {
            return ctor.newInstance();
        }
        catch (InstantiationException | InvocationTargetException | IllegalAccessException e) {
            throw new BinaryObjectException("Failed to instantiate instance: " + cls, e);
        }
    }

    /**
     * @param cls Class.
     * @return Constructor.
     * @throws BinaryObjectException If constructor doesn't exist.
     */
    @SuppressWarnings("ConstantConditions")
    @Nullable private static Constructor<?> constructor(Class<?> cls) throws BinaryObjectException {
        assert cls != null;

        try {
            Constructor<?> ctor = U.forceEmptyConstructor(cls);

            if (ctor == null)
                throw new BinaryObjectException("Failed to find empty constructor for class: " + cls.getName());

            ctor.setAccessible(true);

            return ctor;
        }
        catch (IgniteCheckedException e) {
            throw new BinaryObjectException("Failed to get constructor for class: " + cls.getName(), e);
        }
    }

    /**
     * Determines whether to use {@link OptimizedMarshaller} for serialization or
     * not.
     *
     * @return {@code true} if to use, {@code false} otherwise.
     */
    private boolean initUseOptimizedMarshallerFlag() {
       boolean use;

        try {
            Method writeObj = cls.getDeclaredMethod("writeObject", ObjectOutputStream.class);
            Method readObj = cls.getDeclaredMethod("readObject", ObjectInputStream.class);

            use = !Modifier.isStatic(writeObj.getModifiers()) && !Modifier.isStatic(readObj.getModifiers()) &&
                writeObj.getReturnType() == void.class && readObj.getReturnType() == void.class;
        }
        catch (NoSuchMethodException e) {
            use = false;
        }

        return use;
    }
}
