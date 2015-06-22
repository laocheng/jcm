/*******************************************************************************
 *  Copyright Kevin Lynx (kevinlynx@gmail.com) 2015
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *******************************************************************************/
package com.codemacro.jcm.storage;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZookeeperStorageEngine implements Watcher {
  private static Logger logger = LoggerFactory.getLogger(ZookeeperStorageEngine.class);
  private ZooKeeper zk;
  private String root;
  private CountDownLatch countDownLatch;
  private Map<String, ZookeeperPathWatcher> watchers;
  
  public ZookeeperStorageEngine(String root) {
    this.root = root;
    // StatusStorage depends on ClusterStorage
    this.watchers = new LinkedHashMap<String, ZookeeperPathWatcher>();
  }

  public boolean open(String host, int msTimeout) throws IOException, InterruptedException {
    this.countDownLatch = new CountDownLatch(1);
    this.zk = new ZooKeeper(host, msTimeout, this);
    countDownLatch.await();
    logger.info("connect zookeeper {} success", host);
    return true;
  }

  public void close() throws InterruptedException {
    this.zk.close();
    logger.info("disconnect to zookeeper");
  }

  public ZooKeeper getZooKeeper() {
    return zk;
  }

  public void addWatcher(ZookeeperPathWatcher watcher) {
    String fullPath = root + "/" + watcher.getPath();
    logger.debug("add path watcher on {}", fullPath);
    watcher.onRegister(this, fullPath);
    watchers.put(fullPath, watcher);
  }

  public void process(WatchedEvent event) {
    if (event.getType() == Event.EventType.None) {
      switch (event.getState()) {
      case SyncConnected:
        for (ZookeeperPathWatcher w : watchers.values()) {
          w.onConnected();
        }
        countDownLatch.countDown();
        break;
      case Disconnected:
      case Expired:
        for (ZookeeperPathWatcher w : watchers.values()) {
          w.onDisconnected();
        }
      default:
        break;
      }
    }
    logger.debug("zookeeper event {}", event);
    String path = event.getPath();
    ZookeeperPathWatcher watcher = findWatcher(path);
    if (watcher == null) {
      return ;
    }
    switch (event.getType()) {
    case NodeChildrenChanged:
      watcher.onListChanged();
      break;
    case NodeDataChanged:
      watcher.onChildData(path.substring(1 + path.lastIndexOf('/')));
    default:
      break;
    }
  }
  
  private ZookeeperPathWatcher findWatcher(String fullPath) {
    if (fullPath == null) {
      return null; // not path event
    }
    if (!fullPath.startsWith(this.root)) {
      return null; // root does not match
    }
    if (fullPath.charAt(root.length()) != '/') {
      return null; // root does not match
    }
    int pos = fullPath.indexOf('/', root.length() + 1);
    if (pos < 0) { // only contains sub path
      return watchers.get(fullPath);
    }
    String sub = fullPath.substring(0, pos);
    return watchers.get(sub);
  }
}
