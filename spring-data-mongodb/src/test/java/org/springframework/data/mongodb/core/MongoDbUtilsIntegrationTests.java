/*
 * Copyright 2012-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.mongodb.core;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.*;
import static org.junit.Assume.*;
import static org.springframework.data.mongodb.util.MongoClientVersion.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

import com.mongodb.CommandResult;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.data.authentication.UserCredentials;
import org.springframework.data.util.Version;
import org.springframework.scheduling.concurrent.ThreadPoolExecutorFactoryBean;

import com.mongodb.DB;
import com.mongodb.Mongo;
import com.mongodb.MongoClient;
import com.mongodb.MongoException;

/**
 * Integration tests for {@link MongoDbUtils}.
 *
 * @author Oliver Gierke
 * @author Thomas Darimont
 * @author Christoph Strobl
 * @author Mark Paluch
 */
public class MongoDbUtilsIntegrationTests {

	static final String AUTHENTICATION_DATABASE_NAME = "admin";
	static final String DATABASE_NAME = "dbAuthTests";
	static final UserCredentials CREDENTIALS = new UserCredentials("admin", "admin");

	static Mongo mongo;
	static MongoTemplate template;
	static ThreadPoolExecutorFactoryBean factory;
	static ExecutorService service;
	static Version mongoVersion;

	Exception exception;

	@BeforeClass
	public static void setUp() throws Exception {

		mongo = new MongoClient();
		template = new MongoTemplate(mongo, DATABASE_NAME);

		factory = new ThreadPoolExecutorFactoryBean();
		factory.setCorePoolSize(2);
		factory.setMaxPoolSize(10);
		factory.setWaitForTasksToCompleteOnShutdown(true);
		factory.afterPropertiesSet();

		service = factory.getObject();

		CommandResult result = template.executeCommand("{ buildInfo: 1 }");
		mongoVersion = Version.parse(result.get("version").toString());

		assumeFalse(isMongo3Driver());
		assumeFalse(isMongo4Server(mongoVersion));
	}

	private static boolean isMongo4Server(Version mongoVersion) {
		return mongoVersion.isGreaterThanOrEqualTo(Version.parse("4.0.0"));
	}

	@AfterClass
	public static void tearDown() {

		factory.destroy();

		// Remove test database

		template.execute(new DbCallback<Void>() {
			public Void doInDB(DB db) throws MongoException, DataAccessException {
				db.dropDatabase();
				return null;
			}
		});
	}

	@Test // DATAMONGO-585
	public void authenticatesCorrectlyInMultithreadedEnvironment() throws Exception {

		// Create sample user
		template.execute(new DbCallback<Void>() {
			public Void doInDB(DB db) throws MongoException, DataAccessException {

				ReflectiveDbInvoker.addUser(db, "admin", "admin".toCharArray());
				return null;
			}
		});

		Callable<Void> callable = new Callable<Void>() {
			public Void call() throws Exception {

				try {
					DB db = MongoDbUtils.getDB(mongo, DATABASE_NAME, CREDENTIALS);
					assertThat(db, is(notNullValue()));
				} catch (Exception o_O) {
					MongoDbUtilsIntegrationTests.this.exception = o_O;
				}

				return null;
			}
		};

		List<Callable<Void>> callables = new ArrayList<Callable<Void>>();

		for (int i = 0; i < 10; i++) {
			callables.add(callable);
		}

		service.invokeAll(callables);

		if (exception != null) {
			fail("Exception occurred!" + exception);
		}
	}

	@Test // DATAMONGO-789
	public void authenticatesCorrectlyWithAuthenticationDB() throws Exception {

		// Create sample user
		template.execute(new DbCallback<Void>() {
			public Void doInDB(DB db) throws MongoException, DataAccessException {

				ReflectiveDbInvoker.addUser(db.getSisterDB("admin"), "admin", "admin".toCharArray());
				return null;
			}
		});

		Callable<Void> callable = new Callable<Void>() {
			public Void call() throws Exception {

				try {
					DB db = MongoDbUtils.getDB(mongo, DATABASE_NAME, CREDENTIALS, AUTHENTICATION_DATABASE_NAME);
					assertThat(db, is(notNullValue()));
				} catch (Exception o_O) {
					MongoDbUtilsIntegrationTests.this.exception = o_O;
				}

				return null;
			}
		};

		List<Callable<Void>> callables = new ArrayList<Callable<Void>>();

		for (int i = 0; i < 10; i++) {
			callables.add(callable);
		}

		service.invokeAll(callables);

		if (exception != null) {
			fail("Exception occurred!" + exception);
		}
	}
}
