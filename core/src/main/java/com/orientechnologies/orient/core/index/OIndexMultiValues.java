/*
 * Copyright 2010-2012 Luca Garulli (l.garulli--at--orientechnologies.com)
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
package com.orientechnologies.orient.core.index;

import java.util.*;

import com.orientechnologies.common.collection.OMultiCollectionIterator;
import com.orientechnologies.common.comparator.ODefaultComparator;
import com.orientechnologies.common.concur.resource.OSharedResourceIterator;
import com.orientechnologies.common.listener.OProgressListener;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.db.record.OIdentifiable;
import com.orientechnologies.orient.core.db.record.ridset.sbtree.OSBTreeIndexRIDContainer;
import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.record.ORecord;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.serialization.serializer.stream.OStreamSerializer;
import com.orientechnologies.orient.core.serialization.serializer.stream.OStreamSerializerListRID;
import com.orientechnologies.orient.core.serialization.serializer.stream.OStreamSerializerSBTreeIndexRIDContainer;
import com.orientechnologies.orient.core.type.tree.OMVRBTreeRIDSet;

/**
 * Abstract index implementation that supports multi-values for the same key.
 * 
 * @author Luca Garulli
 * 
 */
public abstract class OIndexMultiValues extends OIndexAbstract<Set<OIdentifiable>> {
  public OIndexMultiValues(final String type, String algorithm, OIndexEngine<Set<OIdentifiable>> indexEngine,
      String valueContainerAlgorithm) {
    super(type, algorithm, indexEngine, valueContainerAlgorithm);
  }

  public Set<OIdentifiable> get(final Object key) {
    checkForRebuild();

    acquireSharedLock();
    try {

      final Set<OIdentifiable> values = indexEngine.get(key);

      if (values == null)
        return Collections.emptySet();

      return new HashSet<OIdentifiable>(values);

    } finally {
      releaseSharedLock();
    }
  }

  public long count(final Object key) {
    checkForRebuild();

    acquireSharedLock();
    try {

      final Set<OIdentifiable> values = indexEngine.get(key);

      if (values == null)
        return 0;

      return values.size();

    } finally {
      releaseSharedLock();
    }
  }

  public OIndexMultiValues put(final Object key, final OIdentifiable iSingleValue) {
    checkForRebuild();

    modificationLock.requestModificationLock();
    try {
      acquireExclusiveLock();
      try {
        checkForKeyType(key);
        Set<OIdentifiable> values = indexEngine.get(key);

        if (values == null) {
          if (ODefaultIndexFactory.SBTREEBONSAI_VALUE_CONTAINER.equals(valueContainerAlgorithm)) {
            values = new OSBTreeIndexRIDContainer(getName());
          } else {
            values = new OMVRBTreeRIDSet(OGlobalConfiguration.MVRBTREE_RID_BINARY_THRESHOLD.getValueAsInteger());
            ((OMVRBTreeRIDSet) values).setAutoConvertToRecord(false);
          }
        }

        if (!iSingleValue.getIdentity().isValid())
          ((ORecord<?>) iSingleValue).save();

        values.add(iSingleValue.getIdentity());

        indexEngine.put(key, values);
        return this;

      } finally {
        releaseExclusiveLock();
      }
    } finally {
      modificationLock.releaseModificationLock();
    }
  }

  public int remove(final OIdentifiable iRecord) {
    checkForRebuild();

    acquireExclusiveLock();
    try {
      return indexEngine.removeValue(iRecord, MultiValuesTransformer.INSTANCE);
    } finally {
      releaseExclusiveLock();
    }
  }

  @Override
  public boolean remove(final Object key, final OIdentifiable value) {
    checkForRebuild();

    modificationLock.requestModificationLock();

    try {
      acquireExclusiveLock();
      try {

        Set<OIdentifiable> recs = indexEngine.get(key);

        if (recs == null)
          return false;

        if (recs.remove(value)) {
          if (recs.isEmpty())
            indexEngine.remove(key);
          else
            indexEngine.put(key, recs);
          return true;
        }
        return false;

      } finally {
        releaseExclusiveLock();
      }
    } finally {
      modificationLock.releaseModificationLock();
    }
  }

  public OIndexMultiValues create(final String name, final OIndexDefinition indexDefinition, final String clusterIndexName,
      final Set<String> clustersToIndex, boolean rebuild, final OProgressListener progressListener) {
    final OStreamSerializer serializer;
    if (ODefaultIndexFactory.SBTREEBONSAI_VALUE_CONTAINER.equals(valueContainerAlgorithm))
      serializer = OStreamSerializerSBTreeIndexRIDContainer.INSTANCE;
    else
      serializer = OStreamSerializerListRID.INSTANCE;

    return (OIndexMultiValues) super.create(name, indexDefinition, clusterIndexName, clustersToIndex, rebuild, progressListener,
        serializer);
  }

  public void getValuesBetween(final Object rangeFrom, final boolean fromInclusive, final Object rangeTo,
      final boolean toInclusive, final IndexValuesResultListener resultListener) {
    checkForRebuild();
    acquireSharedLock();
    try {
      indexEngine.getValuesBetween(rangeFrom, fromInclusive, rangeTo, toInclusive, MultiValuesTransformer.INSTANCE,
          new OIndexEngine.ValuesResultListener() {
            @Override
            public boolean addResult(OIdentifiable identifiable) {
              return resultListener.addResult(identifiable);
            }
          });
    } finally {
      releaseSharedLock();
    }
  }

  public void getValuesMajor(final Object fromKey, final boolean isInclusive, final IndexValuesResultListener valuesResultListener) {
    checkForRebuild();
    acquireSharedLock();
    try {
      indexEngine.getValuesMajor(fromKey, isInclusive, MultiValuesTransformer.INSTANCE, new OIndexEngine.ValuesResultListener() {
        @Override
        public boolean addResult(OIdentifiable identifiable) {
          return valuesResultListener.addResult(identifiable);
        }
      });
    } finally {
      releaseSharedLock();
    }
  }

  public void getValuesMinor(final Object toKey, final boolean isInclusive, final IndexValuesResultListener resultListener) {
    checkForRebuild();
    acquireSharedLock();
    try {
      indexEngine.getValuesMinor(toKey, isInclusive, MultiValuesTransformer.INSTANCE, new OIndexEngine.ValuesResultListener() {
        @Override
        public boolean addResult(OIdentifiable identifiable) {
          return resultListener.addResult(identifiable);
        }
      });
    } finally {
      releaseSharedLock();
    }
  }

  public void getValues(final Collection<?> iKeys, final IndexValuesResultListener resultListener) {
    checkForRebuild();

    final List<Object> sortedKeys = new ArrayList<Object>(iKeys);
    Collections.sort(sortedKeys, ODefaultComparator.INSTANCE);

    acquireSharedLock();
    try {
      for (final Object key : sortedKeys) {
        final Set<OIdentifiable> values = indexEngine.get(key);

        if (values == null)
          continue;

        if (!values.isEmpty()) {
          for (final OIdentifiable value : values) {
            if (!resultListener.addResult(value))
              return;
          }
        }
      }

    } finally {
      releaseSharedLock();
    }
  }

  public void getEntriesMajor(final Object fromKey, final boolean isInclusive,
      final IndexEntriesResultListener entriesResultListener) {
    checkForRebuild();

    acquireSharedLock();
    try {
      indexEngine.getEntriesMajor(fromKey, isInclusive, MultiValuesTransformer.INSTANCE, new OIndexEngine.EntriesResultListener() {
        @Override
        public boolean addResult(ODocument entry) {
          return entriesResultListener.addResult(entry);
        }
      });
    } finally {
      releaseSharedLock();
    }
  }

  public void getEntriesMinor(Object toKey, boolean isInclusive, final IndexEntriesResultListener entriesResultListener) {
    checkForRebuild();

    acquireSharedLock();
    try {
      indexEngine.getEntriesMinor(toKey, isInclusive, MultiValuesTransformer.INSTANCE, new OIndexEngine.EntriesResultListener() {
        @Override
        public boolean addResult(ODocument entry) {
          return entriesResultListener.addResult(entry);
        }
      });
    } finally {
      releaseSharedLock();
    }
  }

  public void getEntriesBetween(Object rangeFrom, Object rangeTo, boolean inclusive,
      final IndexEntriesResultListener indexEntriesResultListener) {
    checkForRebuild();

    final OType[] types = getDefinition().getTypes();
    if (types.length == 1) {
      rangeFrom = OType.convert(rangeFrom, types[0].getDefaultJavaType());
      rangeTo = OType.convert(rangeTo, types[0].getDefaultJavaType());
    }

    acquireSharedLock();
    try {
      indexEngine.getEntriesBetween(rangeFrom, rangeTo, inclusive, MultiValuesTransformer.INSTANCE,
          new OIndexEngine.EntriesResultListener() {
            @Override
            public boolean addResult(ODocument entry) {
              return indexEntriesResultListener.addResult(entry);
            }
          });
    } finally {
      releaseSharedLock();
    }

  }

  public long count(Object rangeFrom, final boolean fromInclusive, Object rangeTo, final boolean toInclusive,
      final int maxValuesToFetch) {
    checkForRebuild();

    final OType[] types = getDefinition().getTypes();
    if (types.length == 1) {
      rangeFrom = OType.convert(rangeFrom, types[0].getDefaultJavaType());
      rangeTo = OType.convert(rangeTo, types[0].getDefaultJavaType());
    }

    if (rangeFrom != null && rangeTo != null && rangeFrom.getClass() != rangeTo.getClass())
      throw new IllegalArgumentException("Range from-to parameters are of different types");

    acquireSharedLock();
    try {
      return indexEngine.count(rangeFrom, fromInclusive, rangeTo, toInclusive, maxValuesToFetch, MultiValuesTransformer.INSTANCE);
    } finally {
      releaseSharedLock();
    }
  }

  public void getEntries(Collection<?> iKeys, IndexEntriesResultListener resultListener) {
    checkForRebuild();

    final List<Object> sortedKeys = new ArrayList<Object>(iKeys);
    Collections.sort(sortedKeys, ODefaultComparator.INSTANCE);

    acquireSharedLock();
    try {
      for (final Object key : sortedKeys) {
        final Set<OIdentifiable> values = indexEngine.get(key);

        if (values == null)
          continue;

        if (!values.isEmpty()) {
          for (final OIdentifiable value : values) {
            final ODocument document = new ODocument();
            document.field("key", key);
            document.field("rid", value.getIdentity());
            document.unsetDirty();

            if (!resultListener.addResult(document))
              return;
          }
        }
      }
    } finally {
      releaseSharedLock();
    }
  }

  public long getSize() {
    checkForRebuild();
    acquireSharedLock();
    try {
      return indexEngine.size(MultiValuesTransformer.INSTANCE);
    } finally {
      releaseSharedLock();
    }

  }

  public long getKeySize() {
    checkForRebuild();
    acquireSharedLock();
    try {
      return indexEngine.size(null);
    } finally {
      releaseSharedLock();
    }
  }

  public Iterator<OIdentifiable> valuesIterator() {
    checkForRebuild();
    acquireSharedLock();
    try {

      return new OSharedResourceIterator<OIdentifiable>(this, new OMultiCollectionIterator<OIdentifiable>(
          indexEngine.valuesIterator()));

    } finally {
      releaseSharedLock();
    }
  }

  @SuppressWarnings({ "rawtypes", "unchecked" })
  public Iterator<OIdentifiable> valuesInverseIterator() {
    checkForRebuild();
    acquireSharedLock();
    try {

      return new OSharedResourceIterator(this, new OMultiCollectionIterator<OIdentifiable>(indexEngine.inverseValuesIterator()));

    } finally {
      releaseSharedLock();
    }
  }

  private static final class MultiValuesTransformer implements OIndexEngine.ValuesTransformer<Set<OIdentifiable>> {
    private static final MultiValuesTransformer INSTANCE = new MultiValuesTransformer();

    @Override
    public Collection<OIdentifiable> transformFromValue(Set<OIdentifiable> value) {
      return value;
    }

    @Override
    public Set<OIdentifiable> transformToValue(Collection<OIdentifiable> collection) {
      return (Set<OIdentifiable>) collection;
    }
  }
}
