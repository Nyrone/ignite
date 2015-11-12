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
import org.apache.ignite.internal.GridDirectTransient;
import org.apache.ignite.internal.IgniteCodeGeneratingFail;
import org.apache.ignite.internal.portable.streams.PortableHeapInputStream;
import org.apache.ignite.internal.processors.cache.CacheObject;
import org.apache.ignite.internal.processors.cache.CacheObjectContext;
import org.apache.ignite.internal.processors.cache.KeyCacheObject;
import org.apache.ignite.internal.processors.cache.portable.CacheObjectBinaryProcessorImpl;
import org.apache.ignite.internal.util.typedef.internal.A;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.plugin.extensions.communication.Message;
import org.apache.ignite.plugin.extensions.communication.MessageReader;
import org.apache.ignite.plugin.extensions.communication.MessageWriter;
import org.apache.ignite.binary.BinaryObjectException;
import org.apache.ignite.binary.BinaryType;
import org.apache.ignite.binary.BinaryObject;
import org.apache.ignite.binary.BinaryField;
import org.jetbrains.annotations.Nullable;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.util.Date;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.ignite.internal.portable.GridPortableMarshaller.BOOLEAN;
import static org.apache.ignite.internal.portable.GridPortableMarshaller.BYTE;
import static org.apache.ignite.internal.portable.GridPortableMarshaller.CHAR;
import static org.apache.ignite.internal.portable.GridPortableMarshaller.DATE;
import static org.apache.ignite.internal.portable.GridPortableMarshaller.DECIMAL;
import static org.apache.ignite.internal.portable.GridPortableMarshaller.DOUBLE;
import static org.apache.ignite.internal.portable.GridPortableMarshaller.FLOAT;
import static org.apache.ignite.internal.portable.GridPortableMarshaller.INT;
import static org.apache.ignite.internal.portable.GridPortableMarshaller.LONG;
import static org.apache.ignite.internal.portable.GridPortableMarshaller.NULL;
import static org.apache.ignite.internal.portable.GridPortableMarshaller.SHORT;
import static org.apache.ignite.internal.portable.GridPortableMarshaller.STRING;
import static org.apache.ignite.internal.portable.GridPortableMarshaller.TIMESTAMP;
import static org.apache.ignite.internal.portable.GridPortableMarshaller.UUID;

/**
 * Portable object implementation.
 */
@IgniteCodeGeneratingFail // Fields arr and start should not be generated by MessageCodeGenerator.
public final class BinaryObjectImpl extends BinaryObjectEx implements Externalizable,
    Message, CacheObject, KeyCacheObject {
    /** */
    public static final byte TYPE_BINARY = 100;

    /** */
    private static final long serialVersionUID = 0L;

    /** */
    @GridDirectTransient
    private PortableContext ctx;

    /** */
    private byte[] arr;

    /** */
    private int start;

    /** */
    @GridDirectTransient
    private Object obj;

    /** */
    @GridDirectTransient
    private boolean detachAllowed;

    /**
     * For {@link Externalizable}.
     */
    public BinaryObjectImpl() {
        // No-op.
    }

    /**
     * @param ctx Context.
     * @param arr Array.
     * @param start Start.
     */
    public BinaryObjectImpl(PortableContext ctx, byte[] arr, int start) {
        assert ctx != null;
        assert arr != null;

        this.ctx = ctx;
        this.arr = arr;
        this.start = start;
    }

    /** {@inheritDoc} */
    @Override public byte cacheObjectType() {
        return TYPE_BINARY;
    }

    /** {@inheritDoc} */
    @Override public boolean isPlatformType() {
        return false;
    }

    /** {@inheritDoc} */
    @Override public boolean internal() {
        return false;
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Nullable @Override public <T> T value(CacheObjectContext ctx, boolean cpy) {
        Object obj0 = obj;

        if (obj0 == null || cpy)
            obj0 = deserializeValue();

        return (T)obj0;
    }

    /** {@inheritDoc} */
    @Override public byte[] valueBytes(CacheObjectContext ctx) throws IgniteCheckedException {
        if (detached())
            return array();

        int len = length();

        byte[] arr0 = new byte[len];

        U.arrayCopy(arr, start, arr0, 0, len);

        return arr0;
    }

    /** {@inheritDoc} */
    @Override public CacheObject prepareForCache(CacheObjectContext ctx) {
        if (detached())
            return this;

        return (BinaryObjectImpl)detach();
    }

    /** {@inheritDoc} */
    @Override public void finishUnmarshal(CacheObjectContext ctx, ClassLoader ldr) throws IgniteCheckedException {
        this.ctx = ((CacheObjectBinaryProcessorImpl)ctx.processor()).portableContext();
    }

    /** {@inheritDoc} */
    @Override public void prepareMarshal(CacheObjectContext ctx) throws IgniteCheckedException {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public int length() {
        return PortablePrimitives.readInt(arr, start + GridPortableMarshaller.TOTAL_LEN_POS);
    }

    /**
     * @return Detached portable object.
     */
    public BinaryObject detach() {
        if (!detachAllowed || detached())
            return this;

        int len = length();

        byte[] arr0 = new byte[len];

        U.arrayCopy(arr, start, arr0, 0, len);

        return new BinaryObjectImpl(ctx, arr0, 0);
    }

    /**
     * @return Detached or not.
     */
    public boolean detached() {
        return start == 0 && length() == arr.length;
    }

    /**
     * @param detachAllowed Detach allowed flag.
     */
    public void detachAllowed(boolean detachAllowed) {
        this.detachAllowed = detachAllowed;
    }

    /**
     * @return Context.
     */
    public PortableContext context() {
        return ctx;
    }

    /**
     * @param ctx Context.
     */
    public void context(PortableContext ctx) {
        this.ctx = ctx;
    }

    /** {@inheritDoc} */
    @Override public byte[] array() {
        return arr;
    }

    /** {@inheritDoc} */
    @Override public int start() {
        return start;
    }

    /** {@inheritDoc} */
    @Override public long offheapAddress() {
        return 0;
    }

    /** {@inheritDoc} */
    @Override protected boolean hasArray() {
        return true;
    }

    /** {@inheritDoc} */
    @Override public int typeId() {
        return PortablePrimitives.readInt(arr, start + GridPortableMarshaller.TYPE_ID_POS);
    }

    /** {@inheritDoc} */
    @Nullable @Override public BinaryType type() throws BinaryObjectException {
        if (ctx == null)
            throw new BinaryObjectException("PortableContext is not set for the object.");

        return ctx.metadata(typeId());
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Nullable @Override public <F> F field(String fieldName) throws BinaryObjectException {
        BinaryReaderExImpl reader = new BinaryReaderExImpl(ctx, arr, start, null);

        return (F)reader.unmarshalField(fieldName);
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Nullable @Override public <F> F field(int fieldId) throws BinaryObjectException {
        BinaryReaderExImpl reader = new BinaryReaderExImpl(ctx, arr, start, null);

        return (F)reader.unmarshalField(fieldId);
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Nullable @Override protected <F> F fieldByOrder(int order) {
        Object val;

        // Calculate field position.
        int schemaOffset = PortablePrimitives.readInt(arr, start + GridPortableMarshaller.SCHEMA_OR_RAW_OFF_POS);

        short flags = PortablePrimitives.readShort(arr, start + GridPortableMarshaller.FLAGS_POS);

        int fieldIdLen = PortableUtils.isCompactFooter(flags) ? 0 : PortableUtils.FIELD_ID_LEN;
        int fieldOffsetLen = PortableUtils.fieldOffsetLength(flags);

        int fieldOffsetPos = start + schemaOffset + order * (fieldIdLen + fieldOffsetLen) + fieldIdLen;

        int fieldPos;

        if (fieldOffsetLen == PortableUtils.OFFSET_1)
            fieldPos = start + ((int)PortablePrimitives.readByte(arr, fieldOffsetPos) & 0xFF);
        else if (fieldOffsetLen == PortableUtils.OFFSET_2)
            fieldPos = start + ((int)PortablePrimitives.readShort(arr, fieldOffsetPos) & 0xFFFF);
        else
            fieldPos = start + PortablePrimitives.readInt(arr, fieldOffsetPos);

        // Read header and try performing fast lookup for well-known types (the most common types go first).
        byte hdr = PortablePrimitives.readByte(arr, fieldPos);

        switch (hdr) {
            case INT:
                val = PortablePrimitives.readInt(arr, fieldPos + 1);

                break;

            case LONG:
                val = PortablePrimitives.readLong(arr, fieldPos + 1);

                break;

            case BOOLEAN:
                val = PortablePrimitives.readBoolean(arr, fieldPos + 1);

                break;

            case SHORT:
                val = PortablePrimitives.readShort(arr, fieldPos + 1);

                break;

            case BYTE:
                val = PortablePrimitives.readByte(arr, fieldPos + 1);

                break;

            case CHAR:
                val = PortablePrimitives.readChar(arr, fieldPos + 1);

                break;

            case FLOAT:
                val = PortablePrimitives.readFloat(arr, fieldPos + 1);

                break;

            case DOUBLE:
                val = PortablePrimitives.readDouble(arr, fieldPos + 1);

                break;

            case STRING: {
                int dataLen = PortablePrimitives.readInt(arr, fieldPos + 1);

                val = new String(arr, fieldPos + 5, dataLen, UTF_8);

                break;
            }

            case DATE: {
                long time = PortablePrimitives.readLong(arr, fieldPos + 1);

                val = new Date(time);

                break;
            }

            case TIMESTAMP: {
                long time = PortablePrimitives.readLong(arr, fieldPos + 1);
                int nanos = PortablePrimitives.readInt(arr, fieldPos + 1 + 8);

                Timestamp ts = new Timestamp(time);

                ts.setNanos(ts.getNanos() + nanos);

                val = ts;

                break;
            }

            case UUID: {
                long most = PortablePrimitives.readLong(arr, fieldPos + 1);
                long least = PortablePrimitives.readLong(arr, fieldPos + 1 + 8);

                val = new UUID(most, least);

                break;
            }

            case DECIMAL: {
                int scale = PortablePrimitives.readInt(arr, fieldPos + 1);

                int dataLen = PortablePrimitives.readInt(arr, fieldPos + 5);
                byte[] data = PortablePrimitives.readByteArray(arr, fieldPos + 9, dataLen);

                BigInteger intVal = new BigInteger(data);

                if (scale < 0) {
                    scale &= 0x7FFFFFFF;

                    intVal = intVal.negate();
                }

                val = new BigDecimal(intVal, scale);

                break;
            }

            case NULL:
                val = null;

                break;

            default: {
                BinaryReaderExImpl reader = new BinaryReaderExImpl(ctx, arr, start, null);

                val = reader.unmarshalFieldByAbsolutePosition(fieldPos);
            }
        }

        return (F)val;
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Nullable @Override protected <F> F field(PortableReaderContext rCtx, String fieldName) {
        BinaryReaderExImpl reader = new BinaryReaderExImpl(ctx,
            new PortableHeapInputStream(arr),
            start,
            null,
            rCtx);

        return (F)reader.unmarshalField(fieldName);
    }

    /** {@inheritDoc} */
    @Override public boolean hasField(String fieldName) {
        BinaryReaderExImpl reader = new BinaryReaderExImpl(ctx, arr, start, null);

        return reader.hasField(fieldName);
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Nullable @Override public <T> T deserialize() throws BinaryObjectException {
        Object obj0 = obj;

        if (obj0 == null)
            obj0 = deserializeValue();

        return (T)obj0;

    }

    /** {@inheritDoc} */
    @Override public BinaryObject clone() throws CloneNotSupportedException {
        return super.clone();
    }

    /** {@inheritDoc} */
    @Override public int hashCode() {
        return PortablePrimitives.readInt(arr, start + GridPortableMarshaller.HASH_CODE_POS);
    }

    /** {@inheritDoc} */
    @Override protected int schemaId() {
        return PortablePrimitives.readInt(arr, start + GridPortableMarshaller.SCHEMA_ID_POS);
    }

    /** {@inheritDoc} */
    @Override protected PortableSchema createSchema() {
        BinaryReaderExImpl reader = new BinaryReaderExImpl(ctx, arr, start, null);

        return reader.getOrCreateSchema();
    }

    /** {@inheritDoc} */
    @Override public BinaryField fieldDescriptor(String fieldName) throws BinaryObjectException {
        A.notNull(fieldName, "fieldName");

        return ctx.createField(typeId(), fieldName);
    }

    /** {@inheritDoc} */
    @Override public void writeExternal(ObjectOutput out) throws IOException {
        out.writeObject(ctx);

        if (detachAllowed) {
            int len = length();

            out.writeInt(len);
            out.write(arr, start, len);
            out.writeInt(0);
        }
        else {
            out.writeInt(arr.length);
            out.write(arr);
            out.writeInt(start);
        }
    }

    /** {@inheritDoc} */
    @Override public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        ctx = (PortableContext)in.readObject();

        arr = new byte[in.readInt()];

        in.readFully(arr);

        start = in.readInt();
    }

    /** {@inheritDoc} */
    @Override public boolean writeTo(ByteBuffer buf, MessageWriter writer) {
        writer.setBuffer(buf);

        if (!writer.isHeaderWritten()) {
            if (!writer.writeHeader(directType(), fieldsCount()))
                return false;

            writer.onHeaderWritten();
        }

        switch (writer.state()) {
            case 0:
                if (!writer.writeByteArray("arr",
                    arr,
                    detachAllowed ? start : 0,
                    detachAllowed ? length() : arr.length))
                    return false;

                writer.incrementState();

            case 1:
                if (!writer.writeInt("start", detachAllowed ? 0 : start))
                    return false;

                writer.incrementState();

        }

        return true;
    }

    /** {@inheritDoc} */
    @Override public boolean readFrom(ByteBuffer buf, MessageReader reader) {
        reader.setBuffer(buf);

        if (!reader.beforeMessageRead())
            return false;

        switch (reader.state()) {
            case 0:
                arr = reader.readByteArray("arr");

                if (!reader.isLastRead())
                    return false;

                reader.incrementState();

            case 1:
                start = reader.readInt("start");

                if (!reader.isLastRead())
                    return false;

                reader.incrementState();

        }

        return true;
    }

    /** {@inheritDoc} */
    @Override public byte directType() {
        return 113;
    }

    /** {@inheritDoc} */
    @Override public byte fieldsCount() {
        return 3;
    }

    /**
     * Runs value deserialization regardless of whether obj already has the deserialized value.
     * Will set obj if descriptor is configured to keep deserialized values.
     */
    private Object deserializeValue() {
        // TODO: IGNITE-1272 - Deserialize with proper class loader.
        BinaryReaderExImpl reader = new BinaryReaderExImpl(ctx, arr, start, null);

        Object obj0 = reader.deserialize();

        PortableClassDescriptor desc = reader.descriptor();

        assert desc != null;

        if (desc.keepDeserialized())
            obj = obj0;

        return obj0;
    }
}