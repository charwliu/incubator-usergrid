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
package org.apache.usergrid.persistence.core.javadriver;


import org.apache.usergrid.persistence.core.astyanax.CassandraFig;

import com.datastax.driver.core.Session;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;


/**
 * TODO.  Provide the ability to do a service hook for realtime tuning without the need of a JVM restart This could be
 * done with governator and service discovery
 *
 * @author tnine
 */
@Singleton
public class DatastaxSessionProvider implements Provider<Session> {

    private final HystrixPool pool;
    private final Session session;


    @Inject
    public DatastaxSessionProvider( final CassandraFig cassandraFig ) {

        pool = new HystrixPoolImpl( new GuicyFigPoolConfiguration( cassandraFig ) );
        session = pool.getSession( "usergrid" );

    }


    @Override
    public Session get() {
        return session;
    }
}