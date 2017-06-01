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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.List;
import java.util.NoSuchElementException;

import org.apache.felix.cm.PersistenceManager;
import org.apache.felix.cm.file.ConfigurationHandler;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZookeeperPersistenceManager implements PersistenceManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ZookeeperPersistenceManager.class);

    public static final String ROOT_PATH = "/configs";

    private ZooKeeper zk;

    public ZookeeperPersistenceManager() {
        try {
            zk = new ZooKeeper("localhost:2181,localhost:2182,localhost:2183", 3000, new Watcher() {
                public void process(WatchedEvent event) {
                    LOGGER.info("WATCHED EVENT:{}", event.toString());
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }

        try {
            if (zk.exists("/configs", false) == null) {
                zk.create("/configs", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
        } catch (KeeperException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean exists(String pid) {
        String zkPath = encodePid(pid);

        try {
            return zk.exists(zkPath, false) != null;
        } catch (KeeperException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public Dictionary load(String pid) throws IOException {
        LOGGER.info("Loading config: {}", pid);

        String zkPath = encodePid(pid);

        ByteArrayInputStream in = null;
        try {
            in = new ByteArrayInputStream(zk.getData(zkPath, false, zk.exists(zkPath, false)));
            return ConfigurationHandler.read(in);
        } catch (KeeperException e) {
            if (e.code()
                    .name()
                    .equals("NONODE")) {
                return null;
            } else {
                e.printStackTrace();
                throw new IOException(e);
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
            throw new IOException(e);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                }
            }
        }
    }

    @Override
    public Enumeration getDictionaries() throws IOException {
        LOGGER.info("Getting Enumeration");
        return new ZookeeperPersistenceManager.ZKDictionaryEnumeration();
    }

    @Override
    public void store(String pid, Dictionary dictionary) throws IOException {
        LOGGER.info("Storing config: {}", pid);

        String zkPath = encodePid(pid);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ConfigurationHandler.write(out, dictionary);

        try {
            if (zk.exists(zkPath, false) == null) {
                zk.create(zkPath,
                        out.toByteArray(),
                        ZooDefs.Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT);
            }
        } catch (KeeperException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void delete(String pid) throws IOException {
        LOGGER.info("Deleting config: {}", pid);

        String zkPath = encodePid(pid);

        try {
            zk.delete(zkPath, -1);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (KeeperException e) {
            e.printStackTrace();
        }
    }

    String encodePid(String pid) {
        //        String zkPath = pid.replace('.', File.separatorChar);
        return ROOT_PATH.concat("/")
                .concat(pid);
    }

    class ZKDictionaryEnumeration implements Enumeration {
        private List<String> configList;

        private int idx = 0;

        private Dictionary next;

        ZKDictionaryEnumeration() {
            try {
                configList = ZookeeperPersistenceManager.this.zk.getChildren(
                        ZookeeperPersistenceManager.this.ROOT_PATH,
                        false);
            } catch (KeeperException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            this.next = this.seek();
        }

        public boolean hasMoreElements() {
            return this.next != null;
        }

        public Object nextElement() {
            if (this.next == null) {
                throw new NoSuchElementException();
            } else {
                Dictionary toReturn = this.next;
                this.next = this.seek();
                return toReturn;
            }
        }

        private Dictionary seek() {
            return this._seek();
        }

        protected Dictionary _seek() {
            if (idx < configList.size()) {
                try {
                    return ZookeeperPersistenceManager.this.load(configList.get(idx));
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    idx++;
                }
            }

            return null;
        }
    }
}
