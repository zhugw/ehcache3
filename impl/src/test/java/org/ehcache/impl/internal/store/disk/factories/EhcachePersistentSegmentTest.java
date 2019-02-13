/*
 * Copyright Terracotta, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ehcache.impl.internal.store.disk.factories;

import org.ehcache.config.Eviction;
import org.ehcache.config.EvictionVeto;
import org.ehcache.impl.internal.store.disk.factories.EhcachePersistentSegmentFactory.EhcachePersistentSegment;
import org.ehcache.impl.internal.store.offheap.HeuristicConfiguration;
import org.ehcache.impl.internal.store.offheap.factories.EhcacheSegmentFactory;
import org.ehcache.impl.internal.store.offheap.factories.EhcacheSegmentFactory.EhcacheSegment.EvictionListener;
import org.ehcache.impl.internal.store.offheap.portability.SerializerPortability;
import org.ehcache.impl.internal.spi.serialization.DefaultSerializationProvider;
import org.ehcache.spi.serialization.SerializationProvider;
import org.ehcache.spi.serialization.Serializer;
import org.ehcache.spi.serialization.UnsupportedTypeException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.terracotta.offheapstore.disk.paging.MappedPageSource;
import org.terracotta.offheapstore.disk.persistent.PersistentPortability;
import org.terracotta.offheapstore.disk.storage.FileBackedStorageEngine;
import org.terracotta.offheapstore.util.Factory;

import java.io.IOException;

import static org.ehcache.impl.internal.store.disk.OffHeapDiskStore.persistent;
import static org.ehcache.impl.internal.spi.TestServiceProvider.providerContaining;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.terracotta.offheapstore.util.MemoryUnit.BYTES;

public class EhcachePersistentSegmentTest {

  @Rule
  public final TemporaryFolder folder = new TemporaryFolder();

  private EhcachePersistentSegmentFactory.EhcachePersistentSegment<String, String> createTestSegment() throws IOException {
    return createTestSegment(Eviction.<String, String>none(), mock(EvictionListener.class));
  }

  private EhcachePersistentSegmentFactory.EhcachePersistentSegment<String, String> createTestSegment(EvictionVeto<String, String> evictionPredicate) throws IOException {
    return createTestSegment(evictionPredicate, mock(EvictionListener.class));
  }

  private EhcachePersistentSegmentFactory.EhcachePersistentSegment<String, String> createTestSegment(EvictionListener<String, String> evictionListener) throws IOException {
    return createTestSegment(Eviction.<String, String>none(), evictionListener);
  }

  private EhcachePersistentSegmentFactory.EhcachePersistentSegment<String, String> createTestSegment(EvictionVeto<String, String> evictionPredicate, EvictionListener<String, String> evictionListener) throws IOException {
    try {
      HeuristicConfiguration configuration = new HeuristicConfiguration(1024 * 1024);
      SerializationProvider serializationProvider = new DefaultSerializationProvider(null);
      serializationProvider.start(providerContaining());
      MappedPageSource pageSource = new MappedPageSource(folder.newFile(), true, configuration.getMaximumSize());
      Serializer<String> keySerializer = serializationProvider.createKeySerializer(String.class, EhcachePersistentSegmentTest.class.getClassLoader());
      Serializer<String> valueSerializer = serializationProvider.createValueSerializer(String.class, EhcachePersistentSegmentTest.class.getClassLoader());
      PersistentPortability<String> keyPortability = persistent(new SerializerPortability<String>(keySerializer));
      PersistentPortability<String> elementPortability = persistent(new SerializerPortability<String>(valueSerializer));
      Factory<FileBackedStorageEngine<String, String>> storageEngineFactory = FileBackedStorageEngine.createFactory(pageSource, configuration.getMaximumSize() / 10, BYTES, keyPortability, elementPortability);
      return new EhcachePersistentSegmentFactory.EhcachePersistentSegment<String, String>(pageSource, storageEngineFactory.newInstance(), 1, true, evictionPredicate, evictionListener);
    } catch (UnsupportedTypeException e) {
      throw new AssertionError(e);
    }
  }

  @Test
  public void testPutVetoedComputesMetadata() throws IOException {
    EhcachePersistentSegment<String, String> segment = createTestSegment(new EvictionVeto<String, String>() {
      @Override
      public boolean vetoes(String key, String value) {
        return "vetoed".equals(key);
      }
    });
    try {
      segment.put("vetoed", "value");
      assertThat(segment.getMetadata("vetoed", EhcacheSegmentFactory.EhcacheSegment.VETOED), is(EhcacheSegmentFactory.EhcacheSegment.VETOED));
    } finally {
      segment.destroy();
    }
  }

  @Test
  public void testPutPinnedVetoedComputesMetadata() throws IOException {
    EhcachePersistentSegment<String, String> segment = createTestSegment(new EvictionVeto<String, String>() {
      @Override
      public boolean vetoes(String key, String value) {
        return "vetoed".equals(key);
      }
    });
    try {
      segment.putPinned("vetoed", "value");
      assertThat(segment.getMetadata("vetoed", EhcacheSegmentFactory.EhcacheSegment.VETOED), is(EhcacheSegmentFactory.EhcacheSegment.VETOED));
    } finally {
      segment.destroy();
    }
  }

  @Test
  public void testVetoedPreventsEviction() throws IOException {
    EhcachePersistentSegment<String, String> segment = createTestSegment();
    try {
      assertThat(segment.evictable(1), is(true));
      assertThat(segment.evictable(EhcacheSegmentFactory.EhcacheSegment.VETOED | 1), is(false));
    } finally {
      segment.destroy();
    }
  }

  @Test
  public void testEvictionFiresEvent() throws IOException {
    EvictionListener<String, String> evictionListener = mock(EvictionListener.class);
    EhcachePersistentSegment<String, String> segment = createTestSegment(evictionListener);
    try {
      segment.put("key", "value");
      segment.evict(segment.getEvictionIndex(), false);
      verify(evictionListener).onEviction("key", "value");
    } finally {
      segment.destroy();
    }
  }
}