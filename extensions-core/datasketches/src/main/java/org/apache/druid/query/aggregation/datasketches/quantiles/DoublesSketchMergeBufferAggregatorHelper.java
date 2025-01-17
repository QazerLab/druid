/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.query.aggregation.datasketches.quantiles;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.apache.datasketches.memory.WritableMemory;
import org.apache.datasketches.quantiles.DoublesUnion;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.IdentityHashMap;

public class DoublesSketchMergeBufferAggregatorHelper
{
  private final int k;
  private final int maxIntermediateSize;
  private final IdentityHashMap<ByteBuffer, WritableMemory> memCache = new IdentityHashMap<>();
  private final IdentityHashMap<ByteBuffer, Int2ObjectMap<DoublesUnion>> unions = new IdentityHashMap<>();

  public DoublesSketchMergeBufferAggregatorHelper(
      final int k,
      final int maxIntermediateSize
  )
  {
    this.k = k;
    this.maxIntermediateSize = maxIntermediateSize;
  }

  public void init(final ByteBuffer buffer, final int position)
  {
    final WritableMemory mem = getMemory(buffer);
    final WritableMemory region = mem.writableRegion(position, maxIntermediateSize);
    final DoublesUnion union = DoublesUnion.builder().setMaxK(k).build(region);
    putUnion(buffer, position, union);
  }

  public Object get(final ByteBuffer buffer, final int position)
  {
    return unions.get(buffer).get(position).getResult();
  }

  public void clear()
  {
    unions.clear();
    memCache.clear();
  }

  // A small number of sketches may run out of the given memory, request more memory on heap and move there.
  // In that case we need to reuse the object from the cache as opposed to wrapping the new buffer.
  public void relocate(int oldPosition, int newPosition, ByteBuffer oldBuffer, ByteBuffer newBuffer)
  {
    DoublesUnion union = unions.get(oldBuffer).get(oldPosition);
    final WritableMemory oldMem = getMemory(oldBuffer).writableRegion(oldPosition, maxIntermediateSize);
    if (union.isSameResource(oldMem)) { // union was not relocated on heap
      final WritableMemory newMem = getMemory(newBuffer).writableRegion(newPosition, maxIntermediateSize);
      union = DoublesUnion.wrap(newMem);
    }
    putUnion(newBuffer, newPosition, union);

    Int2ObjectMap<DoublesUnion> map = unions.get(oldBuffer);
    map.remove(oldPosition);
    if (map.isEmpty()) {
      unions.remove(oldBuffer);
      memCache.remove(oldBuffer);
    }
  }

  /**
   * Retrieves the sketch at a particular position.
   */
  public DoublesUnion getSketchAtPosition(final ByteBuffer buf, final int position)
  {
    return unions.get(buf).get(position);
  }

  private WritableMemory getMemory(final ByteBuffer buffer)
  {
    return memCache.computeIfAbsent(buffer, buf -> WritableMemory.writableWrap(buf, ByteOrder.LITTLE_ENDIAN));
  }

  private void putUnion(final ByteBuffer buffer, final int position, final DoublesUnion union)
  {
    Int2ObjectMap<DoublesUnion> map = unions.computeIfAbsent(buffer, buf -> new Int2ObjectOpenHashMap<>());
    map.put(position, union);
  }
}
