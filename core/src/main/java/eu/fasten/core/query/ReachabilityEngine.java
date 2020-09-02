/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.fasten.core.query;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collection;

import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.Record4;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.json.JSONException;
import org.postgresql.Driver;
import org.rocksdb.RocksDBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.martiansoftware.jsap.JSAP;
import com.martiansoftware.jsap.JSAPException;
import com.martiansoftware.jsap.JSAPResult;
import com.martiansoftware.jsap.Parameter;
import com.martiansoftware.jsap.SimpleJSAP;
import com.martiansoftware.jsap.UnflaggedOption;

import eu.fasten.core.data.FastenURI;
import eu.fasten.core.data.KnowledgeBase;
import eu.fasten.core.data.graphdb.CallGraphData;
import eu.fasten.core.data.graphdb.RocksDao;
import eu.fasten.core.data.metadatadb.codegen.tables.Dependencies;
import eu.fasten.core.data.metadatadb.codegen.tables.PackageVersions;
import eu.fasten.core.data.metadatadb.codegen.tables.Packages;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

/**
 * A sample reachability engine, collecting
 *
 */
public class ReachabilityEngine {

	/**
	 * Establishes database connection.
	 *
	 * @param dbUrl URL of the database to connect
	 * @param user Database user name
	 * @return DSLContext for jOOQ to query the database
	 * @throws SQLException if failed to set up connection
	 * @throws IllegalArgumentException if database URL has incorrect format and cannot be parsed
	 */
	public static DSLContext getDSLContext(final String dbUrl, final String user) throws SQLException, IllegalArgumentException {
		if (!new Driver().acceptsURL(dbUrl)) {
			throw new IllegalArgumentException("Could not parse database URI: " + dbUrl);
		}
		final var pass = System.getenv("FASTEN_DBPASS") != null ? System.getenv("FASTEN_DBPASS") : System.getenv("PGPASSWORD");

		if (pass == null) {
			throw new IllegalArgumentException("No password for DB is provided");
		}
		final var connection = DriverManager.getConnection(dbUrl, user, pass);
		return DSL.using(connection, SQLDialect.POSTGRES);
	}
	private static final Logger LOGGER = LoggerFactory.getLogger(ReachabilityEngine.class);

	public static class ReachabilityQuery implements Query {
		private final FastenURI fastenURI;

		public ReachabilityQuery(final FastenURI fastenURI) {
			this.fastenURI = fastenURI;
		}

		@Override
		public Collection<FastenURI> execute(final KnowledgeBase kb) {
			return kb.reaches(fastenURI);
		}
	}

	public static class CoreachabilityQuery implements Query {
		private final FastenURI fastenURI;

		public CoreachabilityQuery(final FastenURI fastenURI) {
			this.fastenURI = fastenURI;
		}

		@Override
		public Collection<FastenURI> execute(final KnowledgeBase kb) {
			return kb.coreaches(fastenURI);
		}
	}

	public static LongSet getDeps(final DSLContext connector, final Timestamp timestamp, final long index) {
		final Record1<Long> result = connector.select(PackageVersions.PACKAGE_VERSIONS.ID)
				.from(PackageVersions.PACKAGE_VERSIONS)
				.join(Dependencies.DEPENDENCIES)
				.on(Dependencies.DEPENDENCIES.DEPENDENCY_ID.eq(PackageVersions.PACKAGE_VERSIONS.PACKAGE_ID))
				.where(PackageVersions.PACKAGE_VERSIONS.PACKAGE_ID.equal(Long.valueOf(index)).and(PackageVersions.PACKAGE_VERSIONS.CREATED_AT.ge(timestamp)))
				.orderBy(PackageVersions.PACKAGE_VERSIONS.CREATED_AT)
				.fetchOne();
		final var s = new LongOpenHashSet();
		for (int i = 0; i < result.size(); i++) s.add(((Long)result.getValue(i)).longValue());
		return s;
	}

	public static void main(final String[] args) throws JSONException, IOException, ClassNotFoundException, JSAPException, RocksDBException, IllegalArgumentException, SQLException {
		final SimpleJSAP jsap = new SimpleJSAP(ReachabilityEngine.class.getName(), "Searches a given knowledge base (associated to a database)", new Parameter[] {
				new UnflaggedOption("rcgDbPath", JSAP.STRING_PARSER, JSAP.NO_DEFAULT, JSAP.REQUIRED, JSAP.NOT_GREEDY, "The directory of the RocksDB instance containing the revision call graphs."),
				new UnflaggedOption("metaDbURI", JSAP.STRING_PARSER, JSAP.NO_DEFAULT, JSAP.REQUIRED, JSAP.NOT_GREEDY, "The JDBC URI containing the knowledge base metadata."), });

		final JSAPResult jsapResult = jsap.parse(args);
		if (jsap.messagePrinted()) return;

		final String rcgDbPath = jsapResult.getString("rcgDbPath");
		final String metaDbURI = jsapResult.getString("metaDbURI");

		final RocksDao kb = new RocksDao(rcgDbPath, true);
		final DSLContext connector = getDSLContext(metaDbURI, "fasten");

		final BufferedReader br = new BufferedReader(new InputStreamReader(jsapResult.userSpecified("input") ? new FileInputStream(jsapResult.getString("input")) : System.in));

		final String revision = null;
		final String context = null;

		for (;;) {
			System.out.print(">");
			final String q = br.readLine();
			if (q == null) break;
			if (q.length() == 0) continue;
			if (q.charAt(0) == '$') {
				if ("$quit".equals(q)) {
					System.err.println("Exiting");
					break;
				} else if (q.startsWith("$revision")) {
					final String[] name = q.split("[ ]+");
					if (name.length < 3) {
						System.err.println("Missing package name or version");
						continue;
					}
					System.err.println("Selecting package " + name[1] + ", version " + name[2]);
//					System.err.println(connector.select(Packages.PACKAGES.PACKAGE_NAME, PackageVersions.PACKAGE_VERSIONS.VERSION).from(Packages.PACKAGES).join(PackageVersions.PACKAGE_VERSIONS).on(PackageVersions.PACKAGE_VERSIONS.ID.eq(Packages.PACKAGES.ID)).fetch());
					final Result<Record4<Long, String, String, String>> result = connector.select(PackageVersions.PACKAGE_VERSIONS.ID, Packages.PACKAGES.PACKAGE_NAME, Packages.PACKAGES.PROJECT_NAME, PackageVersions.PACKAGE_VERSIONS.VERSION).from(Packages.PACKAGES).join(PackageVersions.PACKAGE_VERSIONS).on(PackageVersions.PACKAGE_VERSIONS.PACKAGE_ID.eq(Packages.PACKAGES.ID)).where(Packages.PACKAGES.PACKAGE_NAME.equal(name[1]).and(PackageVersions.PACKAGE_VERSIONS.VERSION.equal(name[2]))).fetch();
//					final Result<Record4<Long, String, String, String>> result = connector.select(PackageVersions.PACKAGE_VERSIONS.ID, Packages.PACKAGES.PACKAGE_NAME, Packages.PACKAGES.PROJECT_NAME, PackageVersions.PACKAGE_VERSIONS.VERSION).from(Packages.PACKAGES).join(PackageVersions.PACKAGE_VERSIONS).on(PackageVersions.PACKAGE_VERSIONS.PACKAGE_ID.eq(Packages.PACKAGES.ID)).where(Packages.PACKAGES.PACKAGE_NAME.like("%" + name[1] + "%")).fetch();
					if (result.size() == 0) {
						System.err.println("No results in metadata database");
						continue;
					}
					System.err.println(result);
					final long index = ((Long)(result.getValue(0, 0))).longValue();
					final CallGraphData graphData = kb.getGraphData(index);
					if (graphData == null) System.err.println("No data for index " + index);
					else System.err.println("Graph has " + graphData.numNodes() + " nodes, " + graphData.numArcs() + " arcs");
//					System.err.println(connector.select(Packages.PACKAGES.PACKAGE_NAME, PackageVersions.PACKAGE_VERSIONS.VERSION).from(Packages.PACKAGES).join(PackageVersions.PACKAGE_VERSIONS).on(PackageVersions.PACKAGE_VERSIONS.ID.eq(Packages.PACKAGES.ID)).where(Packages.PACKAGES.PACKAGE_NAME.equal(name[1])).fetch());
					// revision =
					continue;
				} else if (q.startsWith("$context")) {
					// TODO
					continue;
				} else {
					System.err.println("Unknown command " + q);
					continue;
				}
			}


			final FastenURI uri;
			try {
				uri = FastenURI.create(q.substring(1));
			} catch (final Exception e) {
				e.printStackTrace(System.err);
				continue;
			}

			/*
			 * Query query; switch (q.charAt(0)) { case '+': query = new ReachabilityQuery(uri); break; case
			 * '-': query = new CoreachabilityQuery(uri); break; default:
			 * System.err.println("Unknown query operator " + q.charAt(0)); continue; } long elapsed =
			 * -System.nanoTime(); final Collection<FastenURI> result = query.execute(kb); if (result == null) {
			 * System.out.println("Method not indexed"); continue; }
			 *
			 * if (result.size() == 0) { System.out.println("Query returned no results"); continue; }
			 *
			 * elapsed += System.nanoTime(); System.err.printf("Elapsed: %.3fs (%d results, %.3f nodes/s)\n",
			 * elapsed / 1E09, result.size(), 1E09 * result.size() / elapsed); final Iterator<FastenURI>
			 * iterator = result.iterator(); for (int i = 0; iterator.hasNext() && i < 10; i++)
			 * System.out.println(iterator.next()); if (result.size() > 10) System.out.println("[...]");
			 */
		}

		kb.close();
		connector.close();
	}
}
