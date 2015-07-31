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

package org.apache.ignite.internal.processors.cache;

import org.apache.ignite.*;
import org.apache.ignite.cache.*;
import org.apache.ignite.cache.affinity.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.managers.communication.*;
import org.apache.ignite.internal.managers.deployment.*;
import org.apache.ignite.internal.managers.discovery.*;
import org.apache.ignite.internal.managers.eventstorage.*;
import org.apache.ignite.internal.managers.swapspace.*;
import org.apache.ignite.internal.processors.affinity.*;
import org.apache.ignite.internal.processors.cache.datastructures.*;
import org.apache.ignite.internal.processors.cache.distributed.dht.*;
import org.apache.ignite.internal.processors.cache.distributed.dht.colocated.*;
import org.apache.ignite.internal.processors.cache.distributed.near.*;
import org.apache.ignite.internal.processors.cache.dr.*;
import org.apache.ignite.internal.processors.cache.jta.*;
import org.apache.ignite.internal.processors.cache.local.*;
import org.apache.ignite.internal.processors.cache.query.*;
import org.apache.ignite.internal.processors.cache.query.continuous.*;
import org.apache.ignite.internal.processors.cache.store.*;
import org.apache.ignite.internal.processors.cache.transactions.*;
import org.apache.ignite.internal.processors.cache.version.*;
import org.apache.ignite.internal.processors.cacheobject.*;
import org.apache.ignite.internal.processors.closure.*;
import org.apache.ignite.internal.processors.offheap.*;
import org.apache.ignite.internal.processors.plugin.*;
import org.apache.ignite.internal.processors.timeout.*;
import org.apache.ignite.internal.util.*;
import org.apache.ignite.internal.util.lang.*;
import org.apache.ignite.internal.util.offheap.unsafe.*;
import org.apache.ignite.internal.util.tostring.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.marshaller.*;
import org.apache.ignite.plugin.security.*;
import org.apache.ignite.plugin.security.SecurityException;
import org.jetbrains.annotations.*;

import javax.cache.*;
import javax.cache.configuration.*;
import javax.cache.expiry.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

import static org.apache.ignite.cache.CacheAtomicityMode.*;
import static org.apache.ignite.cache.CacheMemoryMode.*;
import static org.apache.ignite.cache.CacheRebalanceMode.*;
import static org.apache.ignite.cache.CacheWriteSynchronizationMode.*;

/**
 * Cache context.
 */
@GridToStringExclude
public class GridCacheContext<K, V> implements Externalizable {
    /** */
    private static final long serialVersionUID = 0L;

    /** Deserialization stash. */
    private static final ThreadLocal<IgniteBiTuple<String, String>> stash = new ThreadLocal<IgniteBiTuple<String, String>>() {
        @Override protected IgniteBiTuple<String, String> initialValue() {
            return F.t2();
        }
    };

    /** Empty cache version array. */
    private static final GridCacheVersion[] EMPTY_VERSION = new GridCacheVersion[0];

    /** Kernal context. */
    private GridKernalContext ctx;

    /** Cache shared context. */
    private GridCacheSharedContext<K, V> sharedCtx;

    /** Logger. */
    private IgniteLogger log;

    /** Cache configuration. */
    private CacheConfiguration cacheCfg;

    /** Unsafe memory object for direct memory allocation. */
    private GridUnsafeMemory unsafeMemory;

    /** Affinity manager. */
    private GridCacheAffinityManager affMgr;

    /** Event manager. */
    private GridCacheEventManager evtMgr;

    /** Query manager. */
    private GridCacheQueryManager<K, V> qryMgr;

    /** Continuous query manager. */
    private CacheContinuousQueryManager contQryMgr;

    /** Swap manager. */
    private GridCacheSwapManager swapMgr;

    /** Evictions manager. */
    private GridCacheEvictionManager evictMgr;

    /** Data structures manager. */
    private CacheDataStructuresManager dataStructuresMgr;

    /** Eager TTL manager. */
    private GridCacheTtlManager ttlMgr;

    /** Store manager. */
    private CacheStoreManager storeMgr;

    /** Replication manager. */
    private GridCacheDrManager drMgr;

    /** Conflict resolver manager. */
    private CacheConflictResolutionManager rslvrMgr;

    /** Cache plugin manager. */
    private CachePluginManager pluginMgr;

    /** Managers. */
    private List<GridCacheManager<K, V>> mgrs = new LinkedList<>();

    /** Cache gateway. */
    private GridCacheGateway<K, V> gate;

    /** Grid cache. */
    private GridCacheAdapter<K, V> cache;

    /** Cached local rich node. */
    private ClusterNode locNode;

    /**
     * Thread local operation context. If it's set it means that method call was initiated
     * by child cache of initial cache.
     */
    private ThreadLocal<CacheOperationContext> opCtxPerCall = new ThreadLocal<>();

    /** Cache name. */
    private String cacheName;

    /** Cache ID. */
    private int cacheId;

    /** Cache type. */
    private CacheType cacheType;

    /** IO policy. */
    private byte plc;

    /** Default expiry policy. */
    private ExpiryPolicy expiryPlc;

    /** Cache weak query iterator holder. */
    private CacheWeakQueryIteratorsHolder<Map.Entry<K, V>> itHolder;

    /** Affinity node. */
    private boolean affNode;

    /** Conflict resolver. */
    private CacheVersionConflictResolver conflictRslvr;

    /** */
    private CacheObjectContext cacheObjCtx;

    /** */
    private CountDownLatch startLatch = new CountDownLatch(1);

    /** Start topology version. */
    private AffinityTopologyVersion startTopVer;

    /** Dynamic cache deployment ID. */
    private IgniteUuid dynamicDeploymentId;

    /** Updates allowed flag. */
    private boolean updatesAllowed;

    /**
     * Empty constructor required for {@link Externalizable}.
     */
    public GridCacheContext() {
        // No-op.
    }

    /**
     * @param ctx Kernal context.
     * @param sharedCtx Cache shared context.
     * @param cacheCfg Cache configuration.
     * @param cacheType Cache type.
     * @param affNode {@code True} if local node is affinity node.
     * @param updatesAllowed Updates allowed flag.
     * @param evtMgr Cache event manager.
     * @param swapMgr Cache swap manager.
     * @param storeMgr Store manager.
     * @param evictMgr Cache eviction manager.
     * @param qryMgr Cache query manager.
     * @param contQryMgr Continuous query manager.
     * @param affMgr Affinity manager.
     * @param dataStructuresMgr Cache dataStructures manager.
     * @param ttlMgr TTL manager.
     * @param drMgr Data center replication manager.
     * @param rslvrMgr Conflict resolution manager.
     * @param pluginMgr Cache plugin manager.
     */
    @SuppressWarnings({"unchecked"})
    public GridCacheContext(
        GridKernalContext ctx,
        GridCacheSharedContext sharedCtx,
        CacheConfiguration cacheCfg,
        CacheType cacheType,
        boolean affNode,
        boolean updatesAllowed,

        /*
         * Managers in starting order!
         * ===========================
         */

        GridCacheEventManager evtMgr,
        GridCacheSwapManager swapMgr,
        CacheStoreManager storeMgr,
        GridCacheEvictionManager evictMgr,
        GridCacheQueryManager<K, V> qryMgr,
        CacheContinuousQueryManager contQryMgr,
        GridCacheAffinityManager affMgr,
        CacheDataStructuresManager dataStructuresMgr,
        GridCacheTtlManager ttlMgr,
        GridCacheDrManager drMgr,
        CacheConflictResolutionManager<K, V> rslvrMgr,
        CachePluginManager pluginMgr
    ) {
        assert ctx != null;
        assert sharedCtx != null;
        assert cacheCfg != null;

        assert evtMgr != null;
        assert swapMgr != null;
        assert storeMgr != null;
        assert evictMgr != null;
        assert qryMgr != null;
        assert contQryMgr != null;
        assert affMgr != null;
        assert dataStructuresMgr != null;
        assert ttlMgr != null;
        assert rslvrMgr != null;
        assert pluginMgr != null;

        this.ctx = ctx;
        this.sharedCtx = sharedCtx;
        this.cacheCfg = cacheCfg;
        this.cacheType = cacheType;
        this.affNode = affNode;
        this.updatesAllowed = updatesAllowed;

        /*
         * Managers in starting order!
         * ===========================
         */
        this.evtMgr = add(evtMgr);
        this.swapMgr = add(swapMgr);
        this.storeMgr = add(storeMgr);
        this.evictMgr = add(evictMgr);
        this.qryMgr = add(qryMgr);
        this.contQryMgr = add(contQryMgr);
        this.affMgr = add(affMgr);
        this.dataStructuresMgr = add(dataStructuresMgr);
        this.ttlMgr = add(ttlMgr);
        this.drMgr = add(drMgr);
        this.rslvrMgr = add(rslvrMgr);
        this.pluginMgr = add(pluginMgr);

        log = ctx.log(getClass());

        // Create unsafe memory only if writing values
        unsafeMemory = (cacheCfg.getMemoryMode() == OFFHEAP_VALUES || cacheCfg.getMemoryMode() == OFFHEAP_TIERED) ?
            new GridUnsafeMemory(cacheCfg.getOffHeapMaxMemory()) : null;

        gate = new GridCacheGateway<>(this);

        cacheName = cacheCfg.getName();

        cacheId = CU.cacheId(cacheName);

        plc = cacheType.ioPolicy();

        Factory<ExpiryPolicy> factory = cacheCfg.getExpiryPolicyFactory();

        expiryPlc = factory != null ? factory.create() : null;

        if (expiryPlc instanceof EternalExpiryPolicy)
            expiryPlc = null;

        itHolder = new CacheWeakQueryIteratorsHolder(log);
    }

    /**
     * @param dynamicDeploymentId Dynamic deployment ID.
     */
    void dynamicDeploymentId(IgniteUuid dynamicDeploymentId) {
        this.dynamicDeploymentId = dynamicDeploymentId;
    }

    /**
     * @return Dynamic deployment ID.
     */
    public IgniteUuid dynamicDeploymentId() {
        return dynamicDeploymentId;
    }

    /**
     * Initialize conflict resolver after all managers are started.
     */
    void initConflictResolver() {
        conflictRslvr = rslvrMgr.conflictResolver();
    }

    /**
     * @return {@code True} if local node is affinity node.
     */
    public boolean affinityNode() {
        return affNode;
    }

    /**
     * @throws IgniteCheckedException If failed to wait.
     */
    public void awaitStarted() throws IgniteCheckedException {
        U.await(startLatch);

        GridCachePreloader prldr = preloader();

        if (prldr != null)
            prldr.startFuture().get();
    }

    /**
     * @return Started flag.
     */
    public boolean started() {
        if (startLatch.getCount() != 0)
            return false;

        GridCachePreloader prldr = preloader();

        return prldr == null || prldr.startFuture().isDone();
    }

    /**
     *
     */
    public void onStarted() {
        startLatch.countDown();
    }

    /**
     * @return Start topology version.
     */
    public AffinityTopologyVersion startTopologyVersion() {
        return startTopVer;
    }

    /**
     * @param startTopVer Start topology version.
     */
    public void startTopologyVersion(AffinityTopologyVersion startTopVer) {
        this.startTopVer = startTopVer;
    }

    /**
     * @return Cache default {@link ExpiryPolicy}.
     */
    @Nullable public ExpiryPolicy expiry() {
        return expiryPlc;
    }

    /**
     * @param txEntry TX entry.
     * @return Expiry policy for the given TX entry.
     */
    @Nullable public ExpiryPolicy expiryForTxEntry(IgniteTxEntry txEntry) {
        ExpiryPolicy plc = txEntry.expiry();

        return plc != null ? plc : expiryPlc;
    }

    /**
     * @param mgr Manager to add.
     * @return Added manager.
     */
    @Nullable private <T extends GridCacheManager<K, V>> T add(@Nullable T mgr) {
        if (mgr != null)
            mgrs.add(mgr);

        return mgr;
    }

    /**
     * @return Cache managers.
     */
    public List<GridCacheManager<K, V>> managers() {
        return mgrs;
    }

    /**
     * @return Shared cache context.
     */
    public GridCacheSharedContext<K, V> shared() {
        return sharedCtx;
    }

    /**
     * @return Cache ID.
     */
    public int cacheId() {
        return cacheId;
    }

    /**
     * @return {@code True} if should use system transactions which are isolated from user transactions.
     */
    public boolean systemTx() {
        return cacheType == CacheType.UTILITY;
    }

    /**
     * @return {@code True} if cache created by user.
     */
    public boolean userCache() {
        return cacheType.userCache();
    }

    /**
     * @return IO policy for the given cache.
     */
    public byte ioPolicy() {
        return plc;
    }

    /**
     * @param cache Cache.
     */
    public void cache(GridCacheAdapter<K, V> cache) {
        this.cache = cache;
    }

    /**
     * @return Local cache.
     */
    public GridLocalCache<K, V> local() {
        return (GridLocalCache<K, V>)cache;
    }

    /**
     * @return {@code True} if cache is DHT.
     */
    public boolean isDht() {
        return cache != null && cache.isDht();
    }

    /**
     * @return {@code True} if cache is DHT atomic.
     */
    public boolean isDhtAtomic() {
        return cache != null && cache.isDhtAtomic();
    }

    /**
     * @return {@code True} if cache is colocated (dht with near disabled).
     */
    public boolean isColocated() {
        return cache != null && cache.isColocated();
    }

    /**
     * @return {@code True} if cache is near cache.
     */
    public boolean isNear() {
        return cache != null && cache.isNear();
    }

    /**
     * @return {@code True} if cache is local.
     */
    public boolean isLocal() {
        return cache != null && cache.isLocal();
    }

    /**
     * @return {@code True} if cache is replicated cache.
     */
    public boolean isReplicated() {
        return cacheCfg.getCacheMode() == CacheMode.REPLICATED;
    }

    /**
     * @return {@code True} in case replication is enabled.
     */
    public boolean isDrEnabled() {
        return dr().enabled();
    }

    /**
     * @return {@code True} if entries should not be deleted from cache immediately.
     */
    public boolean deferredDelete() {
        GridCacheAdapter<K, V> cache = this.cache;

        if (cache == null)
            throw new IllegalStateException("Cache stopped: " + cacheName);

        return deferredDelete(cache);
    }

    /**
     * @param cache Cache.
     * @return {@code True} if entries should not be deleted from cache immediately.
     */
    public boolean deferredDelete(GridCacheAdapter<?, ?> cache) {
        return cache.isDht() || cache.isDhtAtomic() || cache.isColocated() ||
            (cache.isNear() && cache.configuration().getAtomicityMode() == ATOMIC);
    }

    /**
     * @param e Entry.
     */
    public void incrementPublicSize(GridCacheMapEntry e) {
        assert deferredDelete();
        assert e != null;
        assert !e.isInternal() : e;

        cache.map().incrementSize(e);

        if (isDht() || isColocated() || isDhtAtomic()) {
            GridDhtLocalPartition part = topology().localPartition(e.partition(), AffinityTopologyVersion.NONE, false);

            if (part != null)
                part.incrementPublicSize();
        }
    }

    /**
     * @param e Entry.
     */
    public void decrementPublicSize(GridCacheMapEntry e) {
        assert deferredDelete();
        assert e != null;
        assert !e.isInternal() : e;

        cache.map().decrementSize(e);

        if (isDht() || isColocated() || isDhtAtomic()) {
            GridDhtLocalPartition part = topology().localPartition(e.partition(), AffinityTopologyVersion.NONE, false);

            if (part != null)
                part.decrementPublicSize();
        }
    }

    /**
     * @return DHT cache.
     */
    public GridDhtCacheAdapter<K, V> dht() {
        return (GridDhtCacheAdapter<K, V>)cache;
    }

    /**
     * @return Transactional DHT cache.
     */
    public GridDhtTransactionalCacheAdapter<K, V> dhtTx() {
        return (GridDhtTransactionalCacheAdapter<K, V>)cache;
    }

    /**
     * @return Colocated cache.
     */
    public GridDhtColocatedCache<K, V> colocated() {
        return (GridDhtColocatedCache<K, V>)cache;
    }

    /**
     * @return Near cache.
     */
    public GridNearCacheAdapter<K, V> near() {
        return (GridNearCacheAdapter<K, V>)cache;
    }

    /**
     * @return Near cache for transactional mode.
     */
    public GridNearTransactionalCache<K, V> nearTx() {
        return (GridNearTransactionalCache<K, V>)cache;
    }

    /**
     * @return Cache gateway.
     */
    public GridCacheGateway<K, V> gate() {
        return gate;
    }

    /**
     * @return Instance of {@link GridUnsafeMemory} object.
     */
    @Nullable public GridUnsafeMemory unsafeMemory() {
        return unsafeMemory;
    }

    /**
     * @return Kernal context.
     */
    public GridKernalContext kernalContext() {
        return ctx;
    }

    /**
     * @return Grid instance.
     */
    public IgniteEx grid() {
        return ctx.grid();
    }

    /**
     * @return Grid name.
     */
    public String gridName() {
        return ctx.gridName();
    }

    /**
     * @return Cache name.
     */
    public String name() {
        return cacheName;
    }

    /**
     * Gets public name for cache.
     *
     * @return Public name of the cache.
     */
    public String namex() {
        return isDht() ? dht().near().name() : name();
    }

    /**
     * Gets public cache name substituting null name by {@code 'default'}.
     *
     * @return Public cache name substituting null name by {@code 'default'}.
     */
    public String namexx() {
        String name = namex();

        return name == null ? "default" : name;
    }

    /**
     * @param key Key to construct tx key for.
     * @return Transaction key.
     */
    public IgniteTxKey txKey(KeyCacheObject key) {
        return new IgniteTxKey(key, cacheId);
    }

    /**
     * @param op Operation to check.
     * @throws SecurityException If security check failed.
     */
    public void checkSecurity(SecurityPermission op) throws SecurityException {
        if (CU.isSystemCache(name()))
            return;

        ctx.security().authorize(name(), op, null);
    }

    /**
     * @return Preloader.
     */
    public GridCachePreloader preloader() {
        return cache().preloader();
    }

    /**
     * @return Local node ID.
     */
    public UUID nodeId() {
        return ctx.localNodeId();
    }

    /**
     * @return {@code True} if rebalance is enabled.
     */
    public boolean rebalanceEnabled() {
        return cacheCfg.getRebalanceMode() != NONE;
    }

    /**
     * @return {@code True} if atomic.
     */
    public boolean atomic() {
        return cacheCfg.getAtomicityMode() == ATOMIC;
    }

    /**
     * @return {@code True} if transactional.
     */
    public boolean transactional() {
        return cacheCfg.getAtomicityMode() == TRANSACTIONAL;
    }

    /**
     * @return Local node.
     */
    public ClusterNode localNode() {
        if (locNode == null)
            locNode = ctx.discovery().localNode();

        return locNode;
    }

    /**
     * @return Local node ID.
     */
    public UUID localNodeId() {
        return ctx.localNodeId();
    }

    /**
     * @param n Node to check.
     * @return {@code True} if node is local.
     */
    public boolean isLocalNode(ClusterNode n) {
        assert n != null;

        return localNode().id().equals(n.id());
    }

    /**
     * @param id Node ID to check.
     * @return {@code True} if node ID is local.
     */
    public boolean isLocalNode(UUID id) {
        assert id != null;

        return localNode().id().equals(id);
    }

    /**
     * @param nodeId Node id.
     * @return Node.
     */
    @Nullable public ClusterNode node(UUID nodeId) {
        assert nodeId != null;

        return ctx.discovery().node(nodeId);
    }

    /**
     * @return Partition topology.
     */
    public GridDhtPartitionTopology topology() {
        GridCacheAdapter<K, V> cache = this.cache;

        if (cache == null)
            throw new IllegalStateException("Cache stopped: " + cacheName);

        assert cache.isNear() || cache.isDht() || cache.isColocated() || cache.isDhtAtomic() : cache;

        return topology(cache);
    }

    /**
     * @return Topology version future.
     */
    public GridDhtTopologyFuture topologyVersionFuture() {
        GridCacheAdapter<K, V> cache = this.cache;

        if (cache == null)
            throw new IllegalStateException("Cache stopped: " + cacheName);

        assert cache.isNear() || cache.isDht() || cache.isColocated() || cache.isDhtAtomic() : cache;

        return topology(cache).topologyVersionFuture();
    }

    /**
     * @param cache Cache.
     * @return Partition topology.
     */
    private GridDhtPartitionTopology topology(GridCacheAdapter<K, V> cache) {
        return cache.isNear() ? ((GridNearCacheAdapter<K, V>)cache).dht().topology() :
            ((GridDhtCacheAdapter<K, V>)cache).topology();
    }

    /**
     * @return Marshaller.
     */
    public Marshaller marshaller() {
        return ctx.config().getMarshaller();
    }

    /**
     * @param ctgr Category to log.
     * @return Logger.
     */
    public IgniteLogger logger(String ctgr) {
        return new GridCacheLogger(this, ctgr);
    }

    /**
     * @param cls Class to log.
     * @return Logger.
     */
    public IgniteLogger logger(Class<?> cls) {
        return logger(cls.getName());
    }

    /**
     * @return Grid configuration.
     */
    public IgniteConfiguration gridConfig() {
        return ctx.config();
    }

    /**
     * @return Grid communication manager.
     */
    public GridIoManager gridIO() {
        return ctx.io();
    }

    /**
     * @return Grid timeout processor.
     */
    public GridTimeoutProcessor time() {
        return ctx.timeout();
    }

    /**
     * @return Grid off-heap processor.
     */
    public GridOffHeapProcessor offheap() {
        return ctx.offheap();
    }

    /**
     * @return Grid deployment manager.
     */
    public GridDeploymentManager gridDeploy() {
        return ctx.deploy();
    }

    /**
     * @return Grid swap space manager.
     */
    public GridSwapSpaceManager gridSwap() {
        return ctx.swap();
    }

    /**
     * @return Grid event storage manager.
     */
    public GridEventStorageManager gridEvents() {
        return ctx.event();
    }

    /**
     * @return Closures processor.
     */
    public GridClosureProcessor closures() {
        return ctx.closure();
    }

    /**
     * @return Grid discovery manager.
     */
    public GridDiscoveryManager discovery() {
        return ctx.discovery();
    }

    /**
     * @return Cache instance.
     */
    public GridCacheAdapter<K, V> cache() {
        return cache;
    }

    /**
     * @return Cache configuration for given cache instance.
     */
    public CacheConfiguration config() {
        return cacheCfg;
    }

    /**
     * @return {@code True} If store writes should be performed from dht transactions. This happens if both
     *      {@code writeBehindEnabled} and {@code writeBehindPreferPrimary} cache configuration properties
     *      are set to {@code true} or the store is local.
     */
    public boolean writeToStoreFromDht() {
        return store().isLocal() || cacheCfg.isWriteBehindEnabled();
    }

    /**
     * @return Cache transaction manager.
     */
    public IgniteTxManager tm() {
         return sharedCtx.tm();
    }

    /**
     * @return Lock order manager.
     */
    public GridCacheVersionManager versions() {
        return sharedCtx.versions();
    }

    /**
     * @return Lock manager.
     */
    public GridCacheMvccManager mvcc() {
        return sharedCtx.mvcc();
    }

    /**
     * @return Event manager.
     */
    public GridCacheEventManager events() {
        return evtMgr;
    }

    /**
     * @return Cache affinity manager.
     */
    public GridCacheAffinityManager affinity() {
        return affMgr;
    }

    /**
     * @return Query manager, {@code null} if disabled.
     */
    public GridCacheQueryManager<K, V> queries() {
        return qryMgr;
    }

    /**
     * @return Continuous query manager, {@code null} if disabled.
     */
    public CacheContinuousQueryManager continuousQueries() {
        return contQryMgr;
    }

    /**
     * @return Iterators Holder.
     */
    public CacheWeakQueryIteratorsHolder<Map.Entry<K, V>> itHolder() {
        return itHolder;
    }

    /**
     * @return Swap manager.
     */
    public GridCacheSwapManager swap() {
        return swapMgr;
    }

    /**
     * @return Store manager.
     */
    public CacheStoreManager store() {
        return storeMgr;
    }

    /**
     * @return Cache deployment manager.
     */
    public GridCacheDeploymentManager<K, V> deploy() {
        return sharedCtx.deploy();
    }

    /**
     * @return Cache communication manager.
     */
    public GridCacheIoManager io() {
        return sharedCtx.io();
    }

    /**
     * @return Eviction manager.
     */
    public GridCacheEvictionManager evicts() {
        return evictMgr;
    }

    /**
     * @return Data structures manager.
     */
    public CacheDataStructuresManager dataStructures() {
        return dataStructuresMgr;
    }

    /**
     * @return DR manager.
     */
    public GridCacheDrManager dr() {
        return drMgr;
    }

    /**
     * @return TTL manager.
     */
    public GridCacheTtlManager ttl() {
        return ttlMgr;
    }

    /**
     * @return JTA manager.
     */
    public CacheJtaManagerAdapter jta() {
        return sharedCtx.jta();
    }

    /**
     * @return Cache plugin manager.
     */
    public CachePluginManager plugin() {
        return pluginMgr;
    }

    /**
     * @param p Predicate.
     * @return {@code True} if given predicate is filter for {@code putIfAbsent} operation.
     */
    public boolean putIfAbsentFilter(@Nullable CacheEntryPredicate[] p) {
        if (p == null || p.length == 0)
            return false;

        for (CacheEntryPredicate p0 : p) {
            if ((p0 instanceof CacheEntrySerializablePredicate) &&
               ((CacheEntrySerializablePredicate)p0).predicate() instanceof CacheEntryPredicateNoValue)
            return true;
        }

        return false;
    }

    /**
     * @return No value filter.
     */
    public CacheEntryPredicate[] noValArray() {
        return new CacheEntryPredicate[]{new CacheEntrySerializablePredicate(new CacheEntryPredicateNoValue())};
    }

    /**
     * @return Has value filter.
     */
    public CacheEntryPredicate[] hasValArray() {
        return new CacheEntryPredicate[]{new CacheEntrySerializablePredicate(new CacheEntryPredicateHasValue())};
    }

    /**
     * @param val Value to check.
     * @return Predicate array that checks for value.
     */
    public CacheEntryPredicate[] equalsValArray(V val) {
        return new CacheEntryPredicate[]{new CacheEntryPredicateContainsValue(toCacheObject(val))};
    }

    /**
     * @param val Value to check.
     * @return Predicate that checks for value.
     */
    public CacheEntryPredicate equalsValue(V val) {
        return new CacheEntryPredicateContainsValue(toCacheObject(val));
    }

    /**
     * @return Empty cache version array.
     */
    public GridCacheVersion[] emptyVersion() {
        return EMPTY_VERSION;
    }

    /**
     * @return Default affinity key mapper.
     */
    public AffinityKeyMapper defaultAffMapper() {
        return cacheObjCtx.defaultAffMapper();
    }

    /**
     * Sets cache object context.
     *
     * @param cacheObjCtx Cache object context.
     */
    public void cacheObjectContext(CacheObjectContext cacheObjCtx) {
        this.cacheObjCtx = cacheObjCtx;
    }

    /**
     * @param p Single predicate.
     * @return Array containing single predicate.
     */
    @SuppressWarnings({"unchecked"})
    public IgnitePredicate<Cache.Entry<K, V>>[] vararg(IgnitePredicate<Cache.Entry<K, V>> p) {
        return p == null ? CU.<K, V>empty() : new IgnitePredicate[]{p};
    }

    /**
     * Same as {@link GridFunc#isAll(Object, IgnitePredicate[])}, but safely unwraps exceptions.
     *
     * @param e Element.
     * @param p Predicates.
     * @return {@code True} if predicates passed.
     * @throws IgniteCheckedException If failed.
     */
    public <K1, V1> boolean isAll(
        GridCacheEntryEx e,
        @Nullable IgnitePredicate<Cache.Entry<K1, V1>>[] p
    ) throws IgniteCheckedException {
        return F.isEmpty(p) || isAll(e.<K1, V1>wrapLazyValue(), p);
    }

    /**
     * Same as {@link GridFunc#isAll(Object, IgnitePredicate[])}, but safely unwraps exceptions.
     *
     * @param e Element.
     * @param p Predicates.
     * @param <E> Element type.
     * @return {@code True} if predicates passed.
     * @throws IgniteCheckedException If failed.
     */
    @SuppressWarnings({"ErrorNotRethrown"})
    public <E> boolean isAll(E e, @Nullable IgnitePredicate<? super E>[] p) throws IgniteCheckedException {
        if (F.isEmpty(p))
            return true;

        try {
            boolean pass = F.isAll(e, p);

            if (log.isDebugEnabled())
                log.debug("Evaluated filters for entry [pass=" + pass + ", entry=" + e + ", filters=" +
                    Arrays.toString(p) + ']');

            return pass;
        }
        catch (RuntimeException ex) {
            throw U.cast(ex);
        }
    }

    /**
     * @param e Entry.
     * @param p Predicates.
     * @return {@code True} if predicates passed.
     * @throws IgniteCheckedException If failed.
     */
    public boolean isAll(GridCacheEntryEx e, CacheEntryPredicate[] p) throws IgniteCheckedException {
        if (p == null || p.length == 0)
            return true;

        try {
            for (CacheEntryPredicate p0 : p) {
                if (p0 != null && !p0.apply(e))
                    return false;
            }
        }
        catch (RuntimeException ex) {
            throw U.cast(ex);
        }

        return true;
    }

    /**
     * @param e Entry.
     * @param p Predicates.
     * @return {@code True} if predicates passed.
     * @throws IgniteCheckedException If failed.
     */
    public boolean isAllLocked(GridCacheEntryEx e, CacheEntryPredicate[] p) throws IgniteCheckedException {
        if (p == null || p.length == 0)
            return true;

        try {
            for (CacheEntryPredicate p0 : p) {
                if (p0 != null) {
                    p0.entryLocked(true);

                    if (!p0.apply(e))
                        return false;
                }
            }
        }
        catch (RuntimeException ex) {
            throw U.cast(ex);
        }
        finally {
            for (CacheEntryPredicate p0 : p) {
                if (p0 != null)
                    p0.entryLocked(false);
            }
        }

        return true;
    }

    /**
     * Sets thread local cache operation context.
     *
     * @param opCtx Flags to set.
     */
    public void operationContextPerCall(@Nullable CacheOperationContext opCtx) {
        if (nearContext())
            dht().near().context().opCtxPerCall.set(opCtx);
        else
            opCtxPerCall.set(opCtx);
    }

    /**
     * Gets thread local cache operation context.
     *
     * @return Operation context per call.
     */
    public CacheOperationContext operationContextPerCall() {
        return nearContext() ? dht().near().context().opCtxPerCall.get() : opCtxPerCall.get();
    }

    /**
     * Gets subject ID per call.
     *
     * @param subjId Optional already existing subject ID.
     * @return Subject ID per call.
     */
    public UUID subjectIdPerCall(@Nullable UUID subjId) {
        if (subjId != null)
            return subjId;

        return subjectIdPerCall(subjId, operationContextPerCall());
    }

    /**
     * Gets subject ID per call.
     *
     * @param subjId Optional already existing subject ID.
     * @param opCtx Optional thread local operation context.
     * @return Subject ID per call.
     */
    public UUID subjectIdPerCall(@Nullable UUID subjId, @Nullable CacheOperationContext opCtx) {
        if (opCtx != null)
            subjId = opCtx.subjectId();

        if (subjId == null)
            subjId = ctx.localNodeId();

        return subjId;
    }

    /**
     * @return {@code true} if the skip store flag is set.
     */
    public boolean skipStore() {
        if (nearContext())
            return dht().near().context().skipStore();

        CacheOperationContext opCtx = opCtxPerCall.get();

        return (opCtx != null && opCtx.skipStore());
    }



    /**
     * @return {@code True} if need check near cache context.
     */
    private boolean nearContext() {
        return isDht() || (isDhtAtomic() && dht().near() != null);
    }

    /**
     * Creates Runnable that can be executed safely in a different thread inheriting
     * the same thread local projection as for the current thread. If no projection is
     * set for current thread then there's no need to create new object and method simply
     * returns given Runnable.
     *
     * @param r Runnable.
     * @return Runnable that can be executed in a different thread with the same
     *      projection as for current thread.
     */
    public Runnable projectSafe(final Runnable r) {
        assert r != null;

        // Have to get operation context per call used by calling thread to use it in a new thread.
        final CacheOperationContext opCtx = operationContextPerCall();

        if (opCtx == null)
            return r;

        return new GPR() {
            @Override public void run() {
                CacheOperationContext old = operationContextPerCall();

                operationContextPerCall(opCtx);

                try {
                    r.run();
                }
                finally {
                    operationContextPerCall(old);
                }
            }
        };
    }

    /**
     * Creates callable that can be executed safely in a different thread inheriting
     * the same thread local projection as for the current thread. If no projection is
     * set for current thread then there's no need to create new object and method simply
     * returns given callable.
     *
     * @param r Callable.
     * @return Callable that can be executed in a different thread with the same
     *      projection as for current thread.
     */
    public <T> Callable<T> projectSafe(final Callable<T> r) {
        assert r != null;

        // Have to get operation context per call used by calling thread to use it in a new thread.
        final CacheOperationContext opCtx = operationContextPerCall();

        if (opCtx == null)
            return r;

        return new GPC<T>() {
            @Override public T call() throws Exception {
                CacheOperationContext old = operationContextPerCall();

                operationContextPerCall(opCtx);

                try {
                    return r.call();
                }
                finally {
                    operationContextPerCall(old);
                }
            }
        };
    }

    /**
     * @return {@code True} if deployment enabled.
     */
    public boolean deploymentEnabled() {
        return ctx.deploy().enabled();
    }

    /**
     * @return {@code True} if swap store of off-heap cache are enabled.
     */
    public boolean isSwapOrOffheapEnabled() {
        return swapMgr.swapEnabled() || isOffHeapEnabled();
    }

    /**
     * @return {@code True} if offheap storage is enabled.
     */
    public boolean isOffHeapEnabled() {
        return swapMgr.offHeapEnabled();
    }

    /**
     * @return {@code True} if store read-through mode is enabled.
     */
    public boolean readThrough() {
        return cacheCfg.isReadThrough() && !skipStore();
    }

    /**
     * @return {@code True} if {@link CacheConfiguration#isLoadPreviousValue()} flag is set.
     */
    public boolean loadPreviousValue() {
        return cacheCfg.isLoadPreviousValue();
    }

    /**
     * @return {@code True} if store write-through is enabled.
     */
    public boolean writeThrough() {
        return cacheCfg.isWriteThrough() && !skipStore();
    }

    /**
     * @return {@code True} if invalidation is enabled.
     */
    public boolean isInvalidate() {
        return cacheCfg.isInvalidate();
    }

    /**
     * @return {@code True} if synchronous commit is enabled.
     */
    public boolean syncCommit() {
        return cacheCfg.getWriteSynchronizationMode() == FULL_SYNC;
    }

    /**
     * @return {@code True} if synchronous rollback is enabled.
     */
    public boolean syncRollback() {
        return cacheCfg.getWriteSynchronizationMode() == FULL_SYNC;
    }

    /**
     * @return {@code True} if only primary node should be updated synchronously.
     */
    public boolean syncPrimary() {
        return cacheCfg.getWriteSynchronizationMode() == PRIMARY_SYNC;
    }

    /**
     * @param nearNodeId Near node ID.
     * @param topVer Topology version.
     * @param entry Entry.
     * @param log Log.
     * @param dhtMap Dht mappings.
     * @param nearMap Near mappings.
     * @return {@code True} if mapped.
     * @throws GridCacheEntryRemovedException If reader for entry is removed.
     */
    public boolean dhtMap(
        UUID nearNodeId,
        AffinityTopologyVersion topVer,
        GridDhtCacheEntry entry,
        GridCacheVersion explicitLockVer,
        IgniteLogger log,
        Map<ClusterNode, List<GridDhtCacheEntry>> dhtMap,
        @Nullable Map<ClusterNode, List<GridDhtCacheEntry>> nearMap
    ) throws GridCacheEntryRemovedException {
        assert !AffinityTopologyVersion.NONE.equals(topVer);

        Collection<ClusterNode> dhtNodes = dht().topology().nodes(entry.partition(), topVer);

        if (log.isDebugEnabled())
            log.debug("Mapping entry to DHT nodes [nodes=" + U.nodeIds(dhtNodes) + ", entry=" + entry + ']');

        Collection<ClusterNode> dhtRemoteNodes = F.view(dhtNodes, F.remoteNodes(nodeId())); // Exclude local node.

        boolean ret = map(entry, dhtRemoteNodes, dhtMap);

        Collection<ClusterNode> nearRemoteNodes = null;

        if (nearMap != null) {
            Collection<UUID> readers = entry.readers();

            Collection<ClusterNode> nearNodes = null;

            if (!F.isEmpty(readers)) {
                nearNodes = discovery().nodes(readers, F0.notEqualTo(nearNodeId));

                if (log.isDebugEnabled())
                    log.debug("Mapping entry to near nodes [nodes=" + U.nodeIds(nearNodes) + ", entry=" + entry + ']');
            }
            else if (log.isDebugEnabled())
                log.debug("Entry has no near readers: " + entry);

            if (nearNodes != null && !nearNodes.isEmpty()) {
                nearRemoteNodes = F.view(nearNodes, F.notIn(dhtNodes));

                ret |= map(entry, nearRemoteNodes, nearMap);
            }
        }

        if (explicitLockVer != null) {
            Collection<ClusterNode> dhtNodeIds = new ArrayList<>(dhtRemoteNodes);
            Collection<ClusterNode> nearNodeIds = F.isEmpty(nearRemoteNodes) ? null : new ArrayList<>(nearRemoteNodes);

            entry.mappings(explicitLockVer, dhtNodeIds, nearNodeIds);
        }

        return ret;
    }

    /**
     * @param entry Entry.
     * @param log Log.
     * @param dhtMap Dht mappings.
     * @param nearMap Near mappings.
     * @return {@code True} if mapped.
     * @throws GridCacheEntryRemovedException If reader for entry is removed.
     */
    public boolean dhtMap(
        GridDhtCacheEntry entry,
        GridCacheVersion explicitLockVer,
        IgniteLogger log,
        Map<ClusterNode, List<GridDhtCacheEntry>> dhtMap,
        Map<ClusterNode, List<GridDhtCacheEntry>> nearMap
    ) throws GridCacheEntryRemovedException {
        assert explicitLockVer != null;

        GridCacheMvccCandidate cand = entry.candidate(explicitLockVer);

        if (cand != null) {
            Collection<ClusterNode> dhtNodes = cand.mappedDhtNodes();

            if (log.isDebugEnabled())
                log.debug("Mapping explicit lock to DHT nodes [nodes=" + U.nodeIds(dhtNodes) + ", entry=" + entry + ']');

            Collection<ClusterNode> nearNodes = cand.mappedNearNodes();

            boolean ret = map(entry, dhtNodes, dhtMap);

            if (nearNodes != null && !nearNodes.isEmpty())
                ret |= map(entry, nearNodes, nearMap);

            return ret;
        }

        return false;
    }

    /**
     * @param entry Entry.
     * @param nodes Nodes.
     * @param map Map.
     * @return {@code True} if mapped.
     */
    private boolean map(GridDhtCacheEntry entry, Iterable<ClusterNode> nodes,
        Map<ClusterNode, List<GridDhtCacheEntry>> map) {
        boolean ret = false;

        if (nodes != null) {
            for (ClusterNode n : nodes) {
                List<GridDhtCacheEntry> entries = map.get(n);

                if (entries == null)
                    map.put(n, entries = new LinkedList<>());

                entries.add(entry);

                ret = true;
            }
        }

        return ret;
    }

    /**
     * Checks if at least one of the given keys belongs to one of the given partitions.
     *
     * @param keys Collection of keys to check.
     * @param movingParts Collection of partitions to check against.
     * @return {@code True} if there exist a key in collection {@code keys} that belongs
     *      to one of partitions in {@code movingParts}
     */
    public boolean hasKey(Iterable<? extends K> keys, Collection<Integer> movingParts) {
        for (K key : keys) {
            if (movingParts.contains(affinity().partition(key)))
                return true;
        }

        return false;
    }

    /**
     * Check whether conflict resolution is required.
     *
     * @return {@code True} in case DR is required.
     */
    public boolean conflictNeedResolve() {
        return conflictRslvr != null;
    }

    /**
     * Resolve DR conflict.
     *
     * @param oldEntry Old entry.
     * @param newEntry New entry.
     * @param atomicVerComp Whether to use atomic version comparator.
     * @return Conflict resolution result.
     * @throws IgniteCheckedException In case of exception.
     */
    public GridCacheVersionConflictContext<K, V> conflictResolve(GridCacheVersionedEntryEx<K, V> oldEntry,
        GridCacheVersionedEntryEx<K, V> newEntry, boolean atomicVerComp) throws IgniteCheckedException {
        assert conflictRslvr != null : "Should not reach this place.";

        GridCacheVersionConflictContext<K, V> ctx = conflictRslvr.resolve(oldEntry, newEntry, atomicVerComp);

        if (ctx.isManualResolve())
            drMgr.onReceiveCacheConflictResolved(ctx.isUseNew(), ctx.isUseOld(), ctx.isMerge());

        return ctx;
    }

    /**
     * @return Data center ID.
     */
    public byte dataCenterId() {
        return dr().dataCenterId();
    }

    /**
     * @param entry Entry.
     * @param ver Version.
     */
    public void onDeferredDelete(GridCacheEntryEx entry, GridCacheVersion ver) {
        assert entry != null;
        assert !Thread.holdsLock(entry) : entry;
        assert ver != null;
        assert deferredDelete() : cache;

        cache.onDeferredDelete(entry, ver);
    }

    /**
     * @param interceptorRes Result of {@link CacheInterceptor#onBeforeRemove} callback.
     * @return {@code True} if interceptor cancels remove.
     */
    public boolean cancelRemove(@Nullable IgniteBiTuple<Boolean, ?> interceptorRes) {
        if (interceptorRes != null) {
            if (interceptorRes.get1() == null) {
                U.warn(log, "CacheInterceptor must not return null as cancellation flag value from " +
                    "'onBeforeRemove' method.");

                return false;
            }
            else
                return interceptorRes.get1();
        }
        else {
            U.warn(log, "CacheInterceptor must not return null from 'onBeforeRemove' method.");

            return false;
        }
    }

    /**
     * @return Portable processor.
     */
    public IgniteCacheObjectProcessor cacheObjects() {
        return kernalContext().cacheObjects();
    }

    /**
     * @return Keep portable flag.
     */
    public boolean keepPortable() {
        CacheOperationContext opCtx = operationContextPerCall();

        return opCtx != null && opCtx.isKeepPortable();
    }

    /**
     * @return {@code True} if OFFHEAP_TIERED memory mode is enabled.
     */
    public boolean offheapTiered() {
        return cacheCfg.getMemoryMode() == OFFHEAP_TIERED && isOffHeapEnabled();
    }

    /**
     * @return {@code True} if should use entry with offheap value pointer.
     */
    public boolean useOffheapEntry() {
        return cacheCfg.getMemoryMode() == OFFHEAP_TIERED || cacheCfg.getMemoryMode() == OFFHEAP_VALUES;
    }

    /**
     * Converts temporary offheap object to heap-based.
     *
     * @param obj Object.
     * @return Heap-based object.
     */
    @Nullable public <T> T unwrapTemporary(@Nullable Object obj) {
        if (!offheapTiered())
            return (T)obj;

        return (T) cacheObjects().unwrapTemporary(this, obj);
    }

    /**
     * Unwraps collection.
     *
     * @param col Collection to unwrap.
     * @param keepPortable Keep portable flag.
     * @return Unwrapped collection.
     */
    public Collection<Object> unwrapPortablesIfNeeded(Collection<Object> col, boolean keepPortable) {
        return cacheObjCtx.unwrapPortablesIfNeeded(col, keepPortable);
    }

    /**
     * Unwraps object for portables.
     *
     * @param o Object to unwrap.
     * @param keepPortable Keep portable flag.
     * @return Unwrapped object.
     */
    @SuppressWarnings("IfMayBeConditional")
    public Object unwrapPortableIfNeeded(Object o, boolean keepPortable) {
        return cacheObjCtx.unwrapPortableIfNeeded(o, keepPortable);
    }

    /**
     * @return Cache object context.
     */
    public CacheObjectContext cacheObjectContext() {
        return cacheObjCtx;
    }

    /**
     * @param obj Object.
     * @return Cache object.
     */
    @Nullable public CacheObject toCacheObject(@Nullable Object obj) {
        return cacheObjects().toCacheObject(cacheObjCtx, obj, true);
    }

    /**
     * @param obj Object.
     * @return Cache key object.
     */
    public KeyCacheObject toCacheKeyObject(Object obj) {
        return cacheObjects().toCacheKeyObject(cacheObjCtx, obj, true);
    }

    /**
     * @param bytes Bytes.
     * @return Cache key object.
     * @throws IgniteCheckedException If failed.
     */
    public KeyCacheObject toCacheKeyObject(byte[] bytes) throws IgniteCheckedException {
        Object obj = ctx.cacheObjects().unmarshal(cacheObjCtx, bytes, deploy().localLoader());

        return cacheObjects().toCacheKeyObject(cacheObjCtx, obj, false);
    }

    /**
     * @param type Type.
     * @param bytes Bytes.
     * @param clsLdrId Class loader ID.
     * @return Cache object.
     * @throws IgniteCheckedException If failed.
     */
    @Nullable public CacheObject unswapCacheObject(byte type, byte[] bytes, @Nullable IgniteUuid clsLdrId)
        throws IgniteCheckedException
    {
        if (ctx.config().isPeerClassLoadingEnabled() && type != CacheObject.TYPE_BYTE_ARR) {
            ClassLoader ldr = clsLdrId != null ? deploy().getClassLoader(clsLdrId) : deploy().localLoader();

            if (ldr == null)
                return null;

            return ctx.cacheObjects().toCacheObject(cacheObjCtx,
                ctx.cacheObjects().unmarshal(cacheObjCtx, bytes, ldr),
                false);
        }

        return ctx.cacheObjects().toCacheObject(cacheObjCtx, type, bytes);
    }

    /**
     * @param valPtr Value pointer.
     * @param tmp If {@code true} can return temporary instance which is valid while entry lock is held.
     * @return Cache object.
     * @throws IgniteCheckedException If failed.
     */
    public CacheObject fromOffheap(long valPtr, boolean tmp) throws IgniteCheckedException {
        assert config().getMemoryMode() == OFFHEAP_TIERED || config().getMemoryMode() == OFFHEAP_VALUES : cacheCfg;
        assert valPtr != 0;

        return ctx.cacheObjects().toCacheObject(this, valPtr, tmp);
    }

    /**
     * @param map Map.
     * @param key Key.
     * @param val Value.
     * @param skipVals Skip values flag.
     * @param keepCacheObjects Keep cache objects flag.
     * @param deserializePortable Deserialize portable flag.
     * @param cpy Copy flag.
     */
    @SuppressWarnings("unchecked")
    public <K1, V1> void addResult(Map<K1, V1> map,
        KeyCacheObject key,
        CacheObject val,
        boolean skipVals,
        boolean keepCacheObjects,
        boolean deserializePortable,
        boolean cpy) {
        assert key != null;
        assert val != null;

        if (!keepCacheObjects) {
            Object key0 = key.value(cacheObjCtx, false);
            Object val0 = skipVals ? true : val.value(cacheObjCtx, cpy);

            if (deserializePortable) {
                key0 = unwrapPortableIfNeeded(key0, false);

                if (!skipVals)
                    val0 = unwrapPortableIfNeeded(val0, false);
            }

            assert key0 != null : key;
            assert val0 != null : val;

            map.put((K1)key0, (V1)val0);
        }
        else
            map.put((K1)key, (V1)(skipVals ? true : val));
    }

    /**
     * @return Updates allowed.
     */
    public boolean updatesAllowed() {
        return updatesAllowed;
    }

    /**
     * Nulling references to potentially leak-prone objects.
     */
    public void cleanup() {
        cache = null;
        cacheCfg = null;
        evictMgr = null;
        qryMgr = null;
        dataStructuresMgr = null;
        cacheObjCtx = null;

        mgrs.clear();
    }

    /**
     * Print memory statistics of all cache managers.
     *
     * NOTE: this method is for testing and profiling purposes only.
     */
    public void printMemoryStats() {
        X.println(">>> ");
        X.println(">>> Cache memory stats [grid=" + ctx.gridName() + ", cache=" + name() + ']');

        cache().printMemoryStats();

        Collection<GridCacheManager> printed = new LinkedList<>();

        for (GridCacheManager mgr : managers()) {
            mgr.printMemoryStats();

            printed.add(mgr);
        }

        if (isNear())
            for (GridCacheManager mgr : near().dht().context().managers())
                if (!printed.contains(mgr))
                    mgr.printMemoryStats();
    }

    /**
     * @param keys Keys.
     * @return Co
     */
    public Collection<KeyCacheObject> cacheKeysView(Collection<?> keys) {
        return F.viewReadOnly(keys, new C1<Object, KeyCacheObject>() {
            @Override public KeyCacheObject apply(Object key) {
                if (key == null)
                    throw new NullPointerException("Null key.");

                return toCacheKeyObject(key);
            }
        });
    }

    /** {@inheritDoc} */
    @Override public void writeExternal(ObjectOutput out) throws IOException {
        U.writeString(out, gridName());
        U.writeString(out, namex());
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"MismatchedQueryAndUpdateOfCollection"})
    @Override public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        IgniteBiTuple<String, String> t = stash.get();

        t.set1(U.readString(in));
        t.set2(U.readString(in));
    }

    /**
     * Reconstructs object on unmarshalling.
     *
     * @return Reconstructed object.
     * @throws ObjectStreamException Thrown in case of unmarshalling error.
     */
    protected Object readResolve() throws ObjectStreamException {
        try {
            IgniteBiTuple<String, String> t = stash.get();

            IgniteKernal grid = IgnitionEx.gridx(t.get1());

            GridCacheAdapter<K, V> cache = grid.internalCache(t.get2());

            if (cache == null)
                throw new IllegalStateException("Failed to find cache for name: " + t.get2());

            return cache.context();
        }
        catch (IllegalStateException e) {
            throw U.withCause(new InvalidObjectException(e.getMessage()), e);
        }
        finally {
            stash.remove();
        }
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return "GridCacheContext: " + name();
    }
}
