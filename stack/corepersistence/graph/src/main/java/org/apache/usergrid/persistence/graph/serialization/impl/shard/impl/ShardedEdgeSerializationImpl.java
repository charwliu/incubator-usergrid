/*
 *
 *  * Licensed to the Apache Software Foundation (ASF) under one
 *  * or more contributor license agreements.  See the NOTICE file
 *  * distributed with this work for additional information
 *  * regarding copyright ownership.  The ASF licenses this file
 *  * to you under the Apache License, Version 2.0 (the
 *  * "License"); you may not use this file except in compliance
 *  * with the License.  You may obtain a copy of the License at
 *  *
 *  *    http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing,
 *  * software distributed under the License is distributed on an
 *  * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  * KIND, either express or implied.  See the License for the
 *  * specific language governing permissions and limitations
 *  * under the License.
 *
 */
package org.apache.usergrid.persistence.graph.serialization.impl.shard.impl;


import java.util.Collection;
import java.util.Iterator;
import java.util.UUID;

import javax.inject.Inject;

import org.apache.usergrid.persistence.core.astyanax.CassandraConfig;
import org.apache.usergrid.persistence.core.astyanax.MultiTennantColumnFamily;
import org.apache.usergrid.persistence.core.astyanax.ScopedRowKey;
import org.apache.usergrid.persistence.core.consistency.TimeService;
import org.apache.usergrid.persistence.core.scope.ApplicationScope;
import org.apache.usergrid.persistence.core.util.ValidationUtils;
import org.apache.usergrid.persistence.graph.Edge;
import org.apache.usergrid.persistence.graph.GraphFig;
import org.apache.usergrid.persistence.graph.MarkedEdge;
import org.apache.usergrid.persistence.graph.SearchByEdge;
import org.apache.usergrid.persistence.graph.SearchByEdgeType;
import org.apache.usergrid.persistence.graph.SearchByIdType;
import org.apache.usergrid.persistence.graph.impl.SimpleMarkedEdge;
import org.apache.usergrid.persistence.graph.serialization.impl.shard.DirectedEdge;
import org.apache.usergrid.persistence.graph.serialization.impl.shard.DirectedEdgeMeta;
import org.apache.usergrid.persistence.graph.serialization.impl.shard.EdgeColumnFamilies;
import org.apache.usergrid.persistence.graph.serialization.impl.shard.EdgeRowKey;
import org.apache.usergrid.persistence.graph.serialization.impl.shard.EdgeShardStrategy;
import org.apache.usergrid.persistence.graph.serialization.impl.shard.RowKey;
import org.apache.usergrid.persistence.graph.serialization.impl.shard.RowKeyType;
import org.apache.usergrid.persistence.graph.serialization.impl.shard.Shard;
import org.apache.usergrid.persistence.graph.serialization.impl.shard.ShardedEdgeSerialization;
import org.apache.usergrid.persistence.graph.serialization.util.GraphValidation;
import org.apache.usergrid.persistence.model.entity.Id;

import com.google.common.base.Optional;
import com.google.inject.Singleton;
import com.netflix.astyanax.Keyspace;
import com.netflix.astyanax.MutationBatch;
import com.netflix.astyanax.Serializer;
import com.netflix.astyanax.util.RangeBuilder;

import static com.google.common.base.Preconditions.checkNotNull;


/**
 * TODO: Rafactor this to use shards only, no shard groups, just collections of shards.  The parent caller can aggregate
 * the results of multiple groups together, this has an impedance mismatch in the API layer.
 */
@Singleton
public class ShardedEdgeSerializationImpl implements ShardedEdgeSerialization {

    protected final Keyspace keyspace;
    protected final CassandraConfig cassandraConfig;
    protected final GraphFig graphFig;
    protected final EdgeShardStrategy writeEdgeShardStrategy;
    protected final TimeService timeService;


    @Inject
    public ShardedEdgeSerializationImpl( final Keyspace keyspace, final CassandraConfig cassandraConfig,
                                         final GraphFig graphFig, final EdgeShardStrategy writeEdgeShardStrategy,
                                         final TimeService timeService ) {


        checkNotNull( "keyspace required", keyspace );
        checkNotNull( "cassandraConfig required", cassandraConfig );
        checkNotNull( "consistencyFig required", graphFig );
        checkNotNull( "writeEdgeShardStrategy required", writeEdgeShardStrategy );
        checkNotNull( "timeService required", timeService );


        this.keyspace = keyspace;
        this.cassandraConfig = cassandraConfig;
        this.graphFig = graphFig;
        this.writeEdgeShardStrategy = writeEdgeShardStrategy;
        this.timeService = timeService;
    }


    @Override
    public MutationBatch writeEdgeFromSource( final EdgeColumnFamilies columnFamilies, final ApplicationScope scope,
                                              final MarkedEdge markedEdge, final Collection<Shard> shards,
                                              final DirectedEdgeMeta directedEdgeMeta, final UUID timestamp ) {
        ValidationUtils.validateApplicationScope( scope );
        GraphValidation.validateEdge( markedEdge );
        ValidationUtils.verifyTimeUuid( timestamp, "timestamp" );

        return new SourceWriteOp( columnFamilies, markedEdge ) {

            @Override
            void writeEdge( final MutationBatch batch,
                            final MultiTennantColumnFamily<ApplicationScope, RowKey, DirectedEdge> columnFamily,
                            final ApplicationScope scope, final RowKey rowKey, final DirectedEdge edge,
                            final Shard shard, final boolean isDeleted ) {

                batch.withRow( columnFamily, ScopedRowKey.fromKey( scope, rowKey ) ).putColumn( edge, isDeleted );

                if ( !isDeleted ) {
                    writeEdgeShardStrategy.increment( scope, shard, 1, directedEdgeMeta );
                }
            }
        }.createBatch( scope, shards, timestamp );
    }


    @Override
    public MutationBatch writeEdgeFromSourceWithTargetType( final EdgeColumnFamilies columnFamilies,
                                                            final ApplicationScope scope, final MarkedEdge markedEdge,
                                                            final Collection<Shard> shards,
                                                            final DirectedEdgeMeta directedEdgeMeta,
                                                            final UUID timestamp ) {
        ValidationUtils.validateApplicationScope( scope );
        GraphValidation.validateEdge( markedEdge );
        ValidationUtils.verifyTimeUuid( timestamp, "timestamp" );


        return new SourceTargetTypeWriteOp( columnFamilies, markedEdge ) {

            @Override
            void writeEdge( final MutationBatch batch,
                            final MultiTennantColumnFamily<ApplicationScope, RowKeyType, DirectedEdge> columnFamily,
                            final ApplicationScope scope, final RowKeyType rowKey, final DirectedEdge edge,
                            final Shard shard, final boolean isDeleted ) {


                batch.withRow( columnFamily, ScopedRowKey.fromKey( scope, rowKey ) ).putColumn( edge, isDeleted );


                if ( !isDeleted ) {
                    writeEdgeShardStrategy.increment( scope, shard, 1, directedEdgeMeta );
                }
            }
        }.createBatch( scope, shards, timestamp );
    }


    @Override
    public MutationBatch writeEdgeToTarget( final EdgeColumnFamilies columnFamilies, final ApplicationScope scope,
                                            final MarkedEdge markedEdge, final Collection<Shard> shards,
                                            final DirectedEdgeMeta targetEdgeMeta, final UUID timestamp ) {
        ValidationUtils.validateApplicationScope( scope );
        GraphValidation.validateEdge( markedEdge );
        ValidationUtils.verifyTimeUuid( timestamp, "timestamp" );


        return new TargetWriteOp( columnFamilies, markedEdge ) {

            @Override
            void writeEdge( final MutationBatch batch,
                            final MultiTennantColumnFamily<ApplicationScope, RowKey, DirectedEdge> columnFamily,
                            final ApplicationScope scope, final RowKey rowKey, final DirectedEdge edge,
                            final Shard shard, final boolean isDeleted ) {

                batch.withRow( columnFamily, ScopedRowKey.fromKey( scope, rowKey ) ).putColumn( edge, isDeleted );


                if ( !isDeleted ) {
                    writeEdgeShardStrategy.increment( scope, shard, 1, targetEdgeMeta );
                }
            }
        }.createBatch( scope, shards, timestamp );
    }


    @Override
    public MutationBatch writeEdgeToTargetWithSourceType( final EdgeColumnFamilies columnFamilies,
                                                          final ApplicationScope scope, final MarkedEdge markedEdge,
                                                          final Collection<Shard> shards,
                                                          final DirectedEdgeMeta directedEdgeMeta,
                                                          final UUID timestamp ) {
        ValidationUtils.validateApplicationScope( scope );
        GraphValidation.validateEdge( markedEdge );
        ValidationUtils.verifyTimeUuid( timestamp, "timestamp" );


        return new TargetSourceTypeWriteOp( columnFamilies, markedEdge ) {

            @Override
            void writeEdge( final MutationBatch batch,
                            final MultiTennantColumnFamily<ApplicationScope, RowKeyType, DirectedEdge> columnFamily,
                            final ApplicationScope scope, final RowKeyType rowKey, final DirectedEdge edge,
                            final Shard shard, final boolean isDeleted ) {

                batch.withRow( columnFamilies.getTargetNodeSourceTypeCfName(), ScopedRowKey.fromKey( scope, rowKey ) )
                     .putColumn( edge, isDeleted );


                if ( !isDeleted ) {
                    writeEdgeShardStrategy.increment( scope, shard, 1, directedEdgeMeta );
                }
            }
        }.createBatch( scope, shards, timestamp );
    }


    @Override
    public MutationBatch writeEdgeVersions( final EdgeColumnFamilies columnFamilies, final ApplicationScope scope,
                                            final MarkedEdge markedEdge, final Collection<Shard> shards,
                                            final DirectedEdgeMeta directedEdgeMeta, final UUID timestamp ) {

        ValidationUtils.validateApplicationScope( scope );
        GraphValidation.validateEdge( markedEdge );
        ValidationUtils.verifyTimeUuid( timestamp, "timestamp" );


        return new EdgeVersions( columnFamilies, markedEdge ) {

            @Override
            void writeEdge( final MutationBatch batch,
                            final MultiTennantColumnFamily<ApplicationScope, EdgeRowKey, Long> columnFamily,
                            final ApplicationScope scope, final EdgeRowKey rowKey, final Long column, final Shard shard,
                            final boolean isDeleted ) {
                batch.withRow( columnFamilies.getGraphEdgeVersions(), ScopedRowKey.fromKey( scope, rowKey ) )
                     .putColumn( column, isDeleted );


                if ( !isDeleted ) {
                    writeEdgeShardStrategy.increment( scope, shard, 1, directedEdgeMeta );
                }
            }
        }.createBatch( scope, shards, timestamp );
    }


    @Override
    public MutationBatch deleteEdgeFromSource( final EdgeColumnFamilies columnFamilies, final ApplicationScope scope,
                                               final MarkedEdge markedEdge, final Collection<Shard> shards,
                                               final DirectedEdgeMeta directedEdgeMeta, final UUID timestamp ) {

        return new SourceWriteOp( columnFamilies, markedEdge ) {

            @Override
            void writeEdge( final MutationBatch batch,
                            final MultiTennantColumnFamily<ApplicationScope, RowKey, DirectedEdge> columnFamily,
                            final ApplicationScope scope, final RowKey rowKey, final DirectedEdge edge,
                            final Shard shard, final boolean isDeleted ) {

                batch.withRow( columnFamily, ScopedRowKey.fromKey( scope, rowKey ) ).deleteColumn( edge );
            }
        }.createBatch( scope, shards, timestamp );
    }


    @Override
    public MutationBatch deleteEdgeFromSourceWithTargetType( final EdgeColumnFamilies columnFamilies,
                                                             final ApplicationScope scope, final MarkedEdge markedEdge,
                                                             final Collection<Shard> shards,
                                                             final DirectedEdgeMeta directedEdgeMeta,
                                                             final UUID timestamp ) {
        return new SourceTargetTypeWriteOp( columnFamilies, markedEdge ) {

            @Override
            void writeEdge( final MutationBatch batch,
                            final MultiTennantColumnFamily<ApplicationScope, RowKeyType, DirectedEdge> columnFamily,
                            final ApplicationScope scope, final RowKeyType rowKey, final DirectedEdge edge,
                            final Shard shard, final boolean isDeleted ) {


                batch.withRow( columnFamilies.getSourceNodeTargetTypeCfName(), ScopedRowKey.fromKey( scope, rowKey ) )
                     .deleteColumn( edge );
            }
        }.createBatch( scope, shards, timestamp );
    }


    @Override
    public MutationBatch deleteEdgeToTarget( final EdgeColumnFamilies columnFamilies, final ApplicationScope scope,
                                             final MarkedEdge markedEdge, final Collection<Shard> shards,
                                             final DirectedEdgeMeta directedEdgeMeta, final UUID timestamp ) {

        return new TargetWriteOp( columnFamilies, markedEdge ) {

            @Override
            void writeEdge( final MutationBatch batch,
                            final MultiTennantColumnFamily<ApplicationScope, RowKey, DirectedEdge> columnFamily,
                            final ApplicationScope scope, final RowKey rowKey, final DirectedEdge edge,
                            final Shard shard, final boolean isDeleted ) {

                batch.withRow( columnFamily, ScopedRowKey.fromKey( scope, rowKey ) ).deleteColumn( edge );
            }
        }.createBatch( scope, shards, timestamp );
    }


    @Override
    public MutationBatch deleteEdgeToTargetWithSourceType( final EdgeColumnFamilies columnFamilies,
                                                           final ApplicationScope scope, final MarkedEdge markedEdge,
                                                           final Collection<Shard> shards,
                                                           final DirectedEdgeMeta directedEdgeMeta,
                                                           final UUID timestamp ) {

        return new TargetSourceTypeWriteOp( columnFamilies, markedEdge ) {

            @Override
            void writeEdge( final MutationBatch batch,
                            final MultiTennantColumnFamily<ApplicationScope, RowKeyType, DirectedEdge> columnFamily,
                            final ApplicationScope scope, final RowKeyType rowKey, final DirectedEdge edge,
                            final Shard shard, final boolean isDeleted ) {

                batch.withRow( columnFamilies.getTargetNodeSourceTypeCfName(), ScopedRowKey.fromKey( scope, rowKey ) )
                     .deleteColumn( edge );
            }
        }.createBatch( scope, shards, timestamp );
    }


    @Override
    public MutationBatch deleteEdgeVersions( final EdgeColumnFamilies columnFamilies, final ApplicationScope scope,
                                             final MarkedEdge markedEdge, final Collection<Shard> shards,
                                             final DirectedEdgeMeta directedEdgeMeta, final UUID timestamp ) {

        return new EdgeVersions( columnFamilies, markedEdge ) {

            @Override
            void writeEdge( final MutationBatch batch,
                            final MultiTennantColumnFamily<ApplicationScope, EdgeRowKey, Long> columnFamily,
                            final ApplicationScope scope, final EdgeRowKey rowKey, final Long column, final Shard shard,
                            final boolean isDeleted ) {
                batch.withRow( columnFamilies.getGraphEdgeVersions(), ScopedRowKey.fromKey( scope, rowKey ) )
                     .deleteColumn( column );
            }
        }.createBatch( scope, shards, timestamp );
    }


    @Override
    public Iterator<MarkedEdge> getEdgeVersions( final EdgeColumnFamilies columnFamilies, final ApplicationScope scope,
                                                 final SearchByEdge search, final Collection<Shard> shards ) {
        ValidationUtils.validateApplicationScope( scope );
        GraphValidation.validateSearchByEdge( search );

        final Id targetId = search.targetNode();
        final Id sourceId = search.sourceNode();
        final String type = search.getType();
        final long maxTimestamp = search.getMaxTimestamp();
        final MultiTennantColumnFamily<ApplicationScope, EdgeRowKey, Long> columnFamily =
                columnFamilies.getGraphEdgeVersions();
        final Serializer<Long> serializer = columnFamily.getColumnSerializer();

        final EdgeSearcher<EdgeRowKey, Long, MarkedEdge> searcher =
                new EdgeSearcher<EdgeRowKey, Long, MarkedEdge>( scope, maxTimestamp, search.last(), shards ) {

                    @Override
                    protected Serializer<Long> getSerializer() {
                        return serializer;
                    }


                    @Override
                    public void setRange( final RangeBuilder builder ) {


                        if ( last.isPresent() ) {
                            super.setRange( builder );
                            return;
                        }

                        //start seeking at a value < our max version
                        builder.setStart( maxTimestamp );
                    }


                    @Override
                    protected EdgeRowKey generateRowKey( long shard ) {
                        return new EdgeRowKey( sourceId, type, targetId, shard );
                    }


                    @Override
                    protected Long getStartColumn( final Edge last ) {
                        return last.getTimestamp();
                    }


                    @Override
                    protected MarkedEdge createEdge( final Long column, final boolean marked ) {
                        return new SimpleMarkedEdge( sourceId, type, targetId, column.longValue(), marked );
                    }


                    @Override
                    public int compare( final MarkedEdge o1, final MarkedEdge o2 ) {
                        return Long.compare( o1.getTimestamp(), o2.getTimestamp() );
                    }
                };

        return new ShardsColumnIterator<>( searcher, columnFamily, keyspace, cassandraConfig.getReadCL(),
                graphFig.getScanPageSize() );
    }


    @Override
    public Iterator<MarkedEdge> getEdgesFromSource( final EdgeColumnFamilies columnFamilies,
                                                    final ApplicationScope scope, final SearchByEdgeType edgeType,
                                                    final Collection<Shard> shards ) {

        ValidationUtils.validateApplicationScope( scope );
        GraphValidation.validateSearchByEdgeType( edgeType );

        final Id sourceId = edgeType.getNode();
        final String type = edgeType.getType();
        final long maxTimestamp = edgeType.getMaxTimestamp();
        final MultiTennantColumnFamily<ApplicationScope, RowKey, DirectedEdge> columnFamily =
                columnFamilies.getSourceNodeCfName();
        final Serializer<DirectedEdge> serializer = columnFamily.getColumnSerializer();


        final SourceEdgeSearcher<RowKey, DirectedEdge, MarkedEdge> searcher =
                new SourceEdgeSearcher<RowKey, DirectedEdge, MarkedEdge>( scope, maxTimestamp, edgeType.last(),
                        shards ) {


                    @Override
                    protected Serializer<DirectedEdge> getSerializer() {
                        return serializer;
                    }


                    @Override
                    protected RowKey generateRowKey( long shard ) {
                        return new RowKey( sourceId, type, shard );
                    }


                    @Override
                    protected DirectedEdge getStartColumn( final Edge last ) {
                        return new DirectedEdge( last.getTargetNode(), last.getTimestamp() );
                    }


                    @Override
                    protected MarkedEdge createEdge( final DirectedEdge edge, final boolean marked ) {
                        return new SimpleMarkedEdge( sourceId, type, edge.id, edge.timestamp, marked );
                    }
                };


        return new ShardsColumnIterator<>( searcher, columnFamily, keyspace, cassandraConfig.getReadCL(),
                graphFig.getScanPageSize() );
    }


    @Override
    public Iterator<MarkedEdge> getEdgesFromSourceByTargetType( final EdgeColumnFamilies columnFamilies,
                                                                final ApplicationScope scope,
                                                                final SearchByIdType edgeType,
                                                                final Collection<Shard> shards ) {

        ValidationUtils.validateApplicationScope( scope );
        GraphValidation.validateSearchByEdgeType( edgeType );

        final Id targetId = edgeType.getNode();
        final String type = edgeType.getType();
        final String targetType = edgeType.getIdType();
        final long maxTimestamp = edgeType.getMaxTimestamp();
        final MultiTennantColumnFamily<ApplicationScope, RowKeyType, DirectedEdge> columnFamily =
                columnFamilies.getSourceNodeTargetTypeCfName();
        final Serializer<DirectedEdge> serializer = columnFamily.getColumnSerializer();


        final SourceEdgeSearcher<RowKeyType, DirectedEdge, MarkedEdge> searcher =
                new SourceEdgeSearcher<RowKeyType, DirectedEdge, MarkedEdge>( scope, maxTimestamp, edgeType.last(),
                        shards ) {

                    @Override
                    protected Serializer<DirectedEdge> getSerializer() {
                        return serializer;
                    }


                    @Override
                    protected RowKeyType generateRowKey( long shard ) {
                        return new RowKeyType( targetId, type, targetType, shard );
                    }


                    @Override
                    protected DirectedEdge getStartColumn( final Edge last ) {
                        return new DirectedEdge( last.getTargetNode(), last.getTimestamp() );
                    }


                    @Override
                    protected MarkedEdge createEdge( final DirectedEdge edge, final boolean marked ) {
                        return new SimpleMarkedEdge( targetId, type, edge.id, edge.timestamp, marked );
                    }
                };

        return new ShardsColumnIterator( searcher, columnFamily, keyspace, cassandraConfig.getReadCL(),
                graphFig.getScanPageSize() );
    }


    @Override
    public Iterator<MarkedEdge> getEdgesToTarget( final EdgeColumnFamilies columnFamilies, final ApplicationScope scope,
                                                  final SearchByEdgeType edgeType, final Collection<Shard> shards ) {
        ValidationUtils.validateApplicationScope( scope );
        GraphValidation.validateSearchByEdgeType( edgeType );

        final Id targetId = edgeType.getNode();
        final String type = edgeType.getType();
        final long maxTimestamp = edgeType.getMaxTimestamp();
        final MultiTennantColumnFamily<ApplicationScope, RowKey, DirectedEdge> columnFamily =
                columnFamilies.getTargetNodeCfName();
        final Serializer<DirectedEdge> serializer = columnFamily.getColumnSerializer();

        final TargetEdgeSearcher<RowKey, DirectedEdge, MarkedEdge> searcher =
                new TargetEdgeSearcher<RowKey, DirectedEdge, MarkedEdge>( scope, maxTimestamp, edgeType.last(),
                        shards ) {

                    @Override
                    protected Serializer<DirectedEdge> getSerializer() {
                        return serializer;
                    }


                    @Override
                    protected RowKey generateRowKey( long shard ) {
                        return new RowKey( targetId, type, shard );
                    }


                    @Override
                    protected DirectedEdge getStartColumn( final Edge last ) {
                        return new DirectedEdge( last.getSourceNode(), last.getTimestamp() );
                    }


                    @Override
                    protected MarkedEdge createEdge( final DirectedEdge edge, final boolean marked ) {
                        return new SimpleMarkedEdge( edge.id, type, targetId, edge.timestamp, marked );
                    }
                };


        return new ShardsColumnIterator<>( searcher, columnFamily, keyspace, cassandraConfig.getReadCL(),
                graphFig.getScanPageSize() );
    }


    @Override
    public Iterator<MarkedEdge> getEdgesToTargetBySourceType( final EdgeColumnFamilies columnFamilies,
                                                              final ApplicationScope scope,
                                                              final SearchByIdType edgeType,
                                                              final Collection<Shard> shards ) {

        ValidationUtils.validateApplicationScope( scope );
        GraphValidation.validateSearchByEdgeType( edgeType );

        final Id targetId = edgeType.getNode();
        final String sourceType = edgeType.getIdType();
        final String type = edgeType.getType();
        final long maxTimestamp = edgeType.getMaxTimestamp();
        final MultiTennantColumnFamily<ApplicationScope, RowKeyType, DirectedEdge> columnFamily =
                columnFamilies.getTargetNodeSourceTypeCfName();
        final Serializer<DirectedEdge> serializer = columnFamily.getColumnSerializer();


        final TargetEdgeSearcher<RowKeyType, DirectedEdge, MarkedEdge> searcher =
                new TargetEdgeSearcher<RowKeyType, DirectedEdge, MarkedEdge>( scope, maxTimestamp, edgeType.last(),
                        shards ) {
                    @Override
                    protected Serializer<DirectedEdge> getSerializer() {
                        return serializer;
                    }


                    @Override
                    protected RowKeyType generateRowKey( final long shard ) {
                        return new RowKeyType( targetId, type, sourceType, shard );
                    }


                    @Override
                    protected DirectedEdge getStartColumn( final Edge last ) {
                        return new DirectedEdge( last.getTargetNode(), last.getTimestamp() );
                    }


                    @Override
                    protected MarkedEdge createEdge( final DirectedEdge edge, final boolean marked ) {
                        return new SimpleMarkedEdge( edge.id, type, targetId, edge.timestamp, marked );
                    }
                };

        return new ShardsColumnIterator<>( searcher, columnFamily, keyspace, cassandraConfig.getReadCL(),
                graphFig.getScanPageSize() );
    }


    /**
     * Class for performing searched on rows based on source id
     */
    private static abstract class SourceEdgeSearcher<R, C, T extends Edge> extends EdgeSearcher<R, C, T> {

        protected SourceEdgeSearcher( final ApplicationScope scope, final long maxTimestamp, final Optional<Edge> last,
                                      final Collection<Shard> shards ) {
            super( scope, maxTimestamp, last, shards );
        }


        public int compare( final T o1, final T o2 ) {
            int compare = Long.compare( o1.getTimestamp(), o2.getTimestamp() );

            if ( compare == 0 ) {
                compare = o1.getTargetNode().compareTo( o2.getTargetNode() );
            }

            return compare;
        }
    }


    /**
     * Class for performing searched on rows based on target id
     */
    private static abstract class TargetEdgeSearcher<R, C, T extends Edge> extends EdgeSearcher<R, C, T> {

        protected TargetEdgeSearcher( final ApplicationScope scope, final long maxTimestamp, final Optional<Edge> last,
                                      final Collection<Shard> shards ) {
            super( scope, maxTimestamp, last, shards );
        }


        public int compare( final T o1, final T o2 ) {
            int compare = Long.compare( o1.getTimestamp(), o2.getTimestamp() );

            if ( compare == 0 ) {
                compare = o1.getTargetNode().compareTo( o2.getTargetNode() );
            }

            return compare;
        }
    }


    /**
     * Simple callback to perform puts and deletes with a common row setup code
     *
     * @param <R> The row key type
     * @param <C> The column type
     */
    private abstract class RowOp<R, C> {


        /**
         * Return the column family used for the write
         */
        protected abstract MultiTennantColumnFamily<ApplicationScope, R, C> getColumnFamily();

        /**
         * Get the row key
         */
        public abstract R getRowKey( final Shard shard );

        /**
         * Get the column family value
         */
        protected abstract C getDirectedEdge();

        /**
         * Get the flag on if it's deleted
         */
        protected abstract boolean isDeleted();


        /**
         * Write the edge with the given data
         */
        abstract void writeEdge( final MutationBatch batch,
                                 final MultiTennantColumnFamily<ApplicationScope, R, C> columnFamily,
                                 final ApplicationScope scope, final R rowKey, final C column, final Shard shard,
                                 final boolean isDeleted );


        /**
         * Create a mutation batch
         */
        public MutationBatch createBatch( final ApplicationScope scope, final Collection<Shard> shards,
                                          final UUID opTimestamp ) {

            final MutationBatch batch =
                    keyspace.prepareMutationBatch().withConsistencyLevel( cassandraConfig.getWriteCL() )
                            .withTimestamp( opTimestamp.timestamp() );


            final C column = getDirectedEdge();
            final MultiTennantColumnFamily<ApplicationScope, R, C> columnFamily = getColumnFamily();
            final boolean isDeleted = isDeleted();


            for ( Shard shard : shards ) {
                final R rowKey = getRowKey( shard );
                writeEdge( batch, columnFamily, scope, rowKey, column, shard, isDeleted );
            }


            return batch;
        }
    }


    /**
     * Perform a write of the source->target
     */
    private abstract class SourceWriteOp extends RowOp<RowKey, DirectedEdge> {

        private final MultiTennantColumnFamily<ApplicationScope, RowKey, DirectedEdge> columnFamily;
        private final Id sourceNodeId;


        private final String type;
        private final boolean isDeleted;
        private final DirectedEdge directedEdge;


        /**
         * Write the source write operation
         */
        private SourceWriteOp( final EdgeColumnFamilies edgeColumnFamilies, final MarkedEdge markedEdge ) {
            this.columnFamily = edgeColumnFamilies.getSourceNodeCfName();

            this.sourceNodeId = markedEdge.getSourceNode();

            this.type = markedEdge.getType();
            this.isDeleted = markedEdge.isDeleted();

            this.directedEdge = new DirectedEdge( markedEdge.getTargetNode(), markedEdge.getTimestamp() );
        }


        @Override
        protected MultiTennantColumnFamily<ApplicationScope, RowKey, DirectedEdge> getColumnFamily() {
            return columnFamily;
        }


        @Override
        public RowKey getRowKey( final Shard shard ) {
            return new RowKey( sourceNodeId, type, shard.getShardIndex() );
        }


        @Override
        protected DirectedEdge getDirectedEdge() {
            return directedEdge;
        }


        @Override
        protected boolean isDeleted() {
            return isDeleted;
        }
    }


    /**
     * Perform a write of the source->target with target type
     */
    private abstract class SourceTargetTypeWriteOp extends RowOp<RowKeyType, DirectedEdge> {

        private final MultiTennantColumnFamily<ApplicationScope, RowKeyType, DirectedEdge> columnFamily;
        private final Id sourceNodeId;
        private final String type;
        private Id targetId;
        private final boolean isDeleted;
        private final DirectedEdge directedEdge;


        /**
         * Write the source write operation
         */
        private SourceTargetTypeWriteOp( final EdgeColumnFamilies edgeColumnFamilies, final MarkedEdge markedEdge ) {
            this.columnFamily = edgeColumnFamilies.getSourceNodeTargetTypeCfName();

            this.sourceNodeId = markedEdge.getSourceNode();

            this.type = markedEdge.getType();
            this.targetId = markedEdge.getTargetNode();
            this.isDeleted = markedEdge.isDeleted();

            this.directedEdge = new DirectedEdge( targetId, markedEdge.getTimestamp() );
        }


        @Override
        protected MultiTennantColumnFamily<ApplicationScope, RowKeyType, DirectedEdge> getColumnFamily() {
            return columnFamily;
        }


        @Override
        public RowKeyType getRowKey( final Shard shard ) {
            return new RowKeyType( sourceNodeId, type, targetId, shard.getShardIndex() );
        }


        @Override
        protected DirectedEdge getDirectedEdge() {
            return directedEdge;
        }


        @Override
        protected boolean isDeleted() {
            return isDeleted;
        }
    }


    /**
     * Perform a write of the target <-- source
     */
    private abstract class TargetWriteOp extends RowOp<RowKey, DirectedEdge> {

        private final MultiTennantColumnFamily<ApplicationScope, RowKey, DirectedEdge> columnFamily;
        private final Id targetNode;


        private final String type;
        private final boolean isDeleted;
        private final DirectedEdge directedEdge;


        /**
         * Write the source write operation
         */
        private TargetWriteOp( final EdgeColumnFamilies edgeColumnFamilies, final MarkedEdge markedEdge ) {
            this.columnFamily = edgeColumnFamilies.getTargetNodeCfName();

            this.targetNode = markedEdge.getTargetNode();

            this.type = markedEdge.getType();
            this.isDeleted = markedEdge.isDeleted();

            this.directedEdge = new DirectedEdge( markedEdge.getSourceNode(), markedEdge.getTimestamp() );
        }


        @Override
        protected MultiTennantColumnFamily<ApplicationScope, RowKey, DirectedEdge> getColumnFamily() {
            return columnFamily;
        }


        @Override
        public RowKey getRowKey( final Shard shard ) {
            return new RowKey( targetNode, type, shard.getShardIndex() );
        }


        @Override
        protected DirectedEdge getDirectedEdge() {
            return directedEdge;
        }


        @Override
        protected boolean isDeleted() {
            return isDeleted;
        }
    }


    /**
     * Perform a write of the target<--source with source type
     */
    private abstract class TargetSourceTypeWriteOp extends RowOp<RowKeyType, DirectedEdge> {

        private final MultiTennantColumnFamily<ApplicationScope, RowKeyType, DirectedEdge> columnFamily;
        private final Id targetNode;

        private final Id sourceNode;

        final String type;

        final boolean isDeleted;
        final DirectedEdge directedEdge;


        /**
         * Write the source write operation
         */
        private TargetSourceTypeWriteOp( final EdgeColumnFamilies edgeColumnFamilies, final MarkedEdge markedEdge ) {
            this.columnFamily = edgeColumnFamilies.getSourceNodeTargetTypeCfName();

            this.targetNode = markedEdge.getTargetNode();
            this.sourceNode = markedEdge.getSourceNode();

            this.type = markedEdge.getType();
            this.isDeleted = markedEdge.isDeleted();

            this.directedEdge = new DirectedEdge( sourceNode, markedEdge.getTimestamp() );
        }


        @Override
        protected MultiTennantColumnFamily<ApplicationScope, RowKeyType, DirectedEdge> getColumnFamily() {
            return columnFamily;
        }


        @Override
        public RowKeyType getRowKey( final Shard shard ) {
            return new RowKeyType( targetNode, type, sourceNode, shard.getShardIndex() );
        }


        @Override
        protected DirectedEdge getDirectedEdge() {
            return directedEdge;
        }


        @Override
        protected boolean isDeleted() {
            return isDeleted;
        }
    }


    /**
     * Perform a write of the edge versions
     */
    private abstract class EdgeVersions extends RowOp<EdgeRowKey, Long> {

        private final MultiTennantColumnFamily<ApplicationScope, EdgeRowKey, Long> columnFamily;
        private final Id targetNode;

        private final Id sourceNode;

        final String type;

        final boolean isDeleted;
        final Long edgeVersion;


        /**
         * Write the source write operation
         */
        private EdgeVersions( final EdgeColumnFamilies edgeColumnFamilies, final MarkedEdge markedEdge ) {
            this.columnFamily = edgeColumnFamilies.getGraphEdgeVersions();

            this.targetNode = markedEdge.getTargetNode();
            this.sourceNode = markedEdge.getSourceNode();

            this.type = markedEdge.getType();
            this.isDeleted = markedEdge.isDeleted();

            this.edgeVersion = markedEdge.getTimestamp();
        }


        @Override
        protected MultiTennantColumnFamily<ApplicationScope, EdgeRowKey, Long> getColumnFamily() {
            return columnFamily;
        }


        @Override
        public EdgeRowKey getRowKey( final Shard shard ) {
            return new EdgeRowKey( sourceNode, type, targetNode, shard.getShardIndex() );
        }


        @Override
        protected Long getDirectedEdge() {
            return edgeVersion;
        }


        @Override
        protected boolean isDeleted() {
            return isDeleted;
        }
    }
}