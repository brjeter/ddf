/**
 * Copyright (c) Codice Foundation
 * <p>
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. A copy of the GNU Lesser General Public License
 * is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */
package org.codice.ddf.config.zookeeper;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;

import java.io.IOException;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Hashtable;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ZookeeperPersistenceManagerTest {
    private ZookeeperPersistenceManager zkPersistenceManager;

    @Before
    public void setUp() {
        zkPersistenceManager = new ZookeeperPersistenceManager();
    }

    @Test
    public void testStoreAndDelete() throws IOException {
        Dictionary dict = new Hashtable<String, String>();
        dict.put("test1", "test1");
        dict.put("test2", "test2");

        zkPersistenceManager.store("org.codice", dict);

        Dictionary returnedDict = zkPersistenceManager.load("org.codice");

        assertThat(returnedDict.size(), is(2));
        assertThat(returnedDict.get("test1"), is("test1"));
        assertThat(returnedDict.get("test2"), is("test2"));

        zkPersistenceManager.delete("org.codice");

        assertThat(zkPersistenceManager.load("org.codice"), is(nullValue()));
    }

    @Test
    public void testEnumeration() throws IOException {
        for (int i = 0; i < 10; i++) {
            Dictionary dict = new Hashtable<String, String>();
            dict.put("test" + i, "test" + i);

            zkPersistenceManager.store("org.codice" + i, dict);
        }

        Enumeration enumeration = zkPersistenceManager.getDictionaries();

        while(enumeration.hasMoreElements()) {
            System.out.println(enumeration.nextElement());
        };
    }
}
