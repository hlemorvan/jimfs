/*
 * Copyright 2013 Google Inc.
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

package com.google.jimfs.internal;

import static com.google.jimfs.internal.Name.PARENT;
import static com.google.jimfs.internal.Name.SELF;
import static com.google.jimfs.internal.Util.smearHash;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;

import java.util.Iterator;

import javax.annotation.Nullable;

/**
 * A table of {@linkplain DirectoryEntry directory entries}.
 *
 * @author Colin Decker
 */
final class DirectoryTable implements FileContent, Iterable<DirectoryEntry> {

  /*
   * This class uses its own simple hash table implementation to avoid allocation of redundant
   * Map.Entry objects. DirectoryEntry objects are able to serve the same purpose.
   */
  private DirectoryEntry[] table = new DirectoryEntry[16];
  private int size;

  /**
   * The entry linking to this directory in its parent directory.
   */
  private DirectoryEntry entry;

  /**
   * Creates a copy of this table. The copy does <i>not</i> contain a copy of the entries in this
   * table.
   */
  @Override
  public DirectoryTable copy() {
    return new DirectoryTable();
  }

  @Override
  public long sizeInBytes() {
    return 0;
  }

  @Override
  public void delete() {
  }

  /**
   * Sets this directory as the super root.
   */
  public void setSuperRoot(File file) {
    // just set this table's entry to include the file
    this.entry = new DirectoryEntry(file, Name.EMPTY, file);
  }

  /**
   * Sets this directory as a root directory, linking ".." to itself.
   */
  public void setRoot() {
    this.entry = new DirectoryEntry(self(), name(), self());
    remove(PARENT);
    put(PARENT, self());
  }

  /**
   * Returns the entry linking to this directory in its parent.
   */
  public DirectoryEntry entry() {
    return entry;
  }

  /**
   * Returns the file for this directory.
   */
  public File self() {
    return entry.file();
  }

  /**
   * Returns the name of this directory.
   */
  public Name name() {
    return entry.name();
  }

  /**
   * Returns the parent of this directory.
   */
  public File parent() {
    return entry.directory();
  }

  /**
   * Called when this directory is linked in a parent directory. The given entry is the new entry
   * linking to this directory.
   */
  public void linked(DirectoryEntry entry) {
    this.entry = entry;
    put(SELF, entry.file());
    put(PARENT, entry.directory());
  }

  /**
   * Called when this directory is unlinked from its parent directory.
   */
  public void unlinked() {
    remove(SELF);
    remove(PARENT);
    entry = null;
  }

  /**
   * Returns the number of entries in this directory.
   */
  @VisibleForTesting
  int entryCount() {
    return size;
  }

  /**
   * Returns true if this directory has no entries other than those to itself and its parent.
   */
  public boolean isEmpty() {
    return size <= 2;
  }

  /**
   * Links the given name to the given file in this table.
   *
   * @throws IllegalArgumentException if {@code name} is a reserved name such as "." or if an
   *     entry already exists for the it
   */
  public void link(Name name, File file) {
    DirectoryEntry entry = put(checkValidName(name, "link"), file);
    if (file.isDirectory()) {
      file.asDirectoryTable().linked(entry);
    }
  }

  /**
   * Unlinks the given name from any key it is linked to in this table. Returns the file key that
   * was linked to the name, or {@code null} if no such mapping was present.
   *
   * @throws IllegalArgumentException if {@code name} is a reserved name such as "."
   */
  public void unlink(Name name) {
    DirectoryEntry entry = remove(checkValidName(name, "unlink"));
    File file = entry.file();
    if (file.isDirectory()) {
      file.asDirectoryTable().unlinked();
    }
  }

  @SuppressWarnings("unchecked") // safe cast
  private static final Ordering<Name> ORDERING_BY_STRING =
      (Ordering<Name>) (Ordering) Ordering.usingToString();

  /**
   * Creates an immutable sorted snapshot of the names this directory contains, excluding "." and
   * "..".
   */
  @SuppressWarnings("unchecked") // safe cast
  public ImmutableSortedSet<Name> snapshot() {
    ImmutableSortedSet.Builder<Name> builder = ImmutableSortedSet.orderedBy(ORDERING_BY_STRING);

    for (DirectoryEntry entry : this) {
      if (!isReserved(entry.name())) {
        builder.add(entry.name());
      }
    }

    return builder.build();
  }

  private static int indexOf(Name name, int tableLength) {
    return smearHash(name.hashCode()) & (tableLength - 1);
  }

  private float loadFactor() {
    return ((float) size) / table.length;
  }

  /**
   * Returns the entry for the given name in this table or {@code null} if no such entry exists.
   */
  @Nullable
  public DirectoryEntry get(Name name) {
    int index = indexOf(name, table.length);
    DirectoryEntry entry = table[index];
    while (entry != null) {
      if (name.equals(entry.name())) {
        return entry;
      }

      entry = entry.next;
    }
    return null;
  }

  /**
   * Adds the given entry to this table.
   */
  private DirectoryEntry put(Name name, File file) {
    int index = indexOf(name, table.length);
    DirectoryEntry prev = null;
    DirectoryEntry curr = table[index];
    while (curr != null) {
      if (curr.name().equals(name)) {
        throw new IllegalArgumentException("entry '" + name + "' already exists");
      }

      prev = curr;
      curr = curr.next;
    }

    DirectoryEntry newEntry = new DirectoryEntry(self(), name, file);
    size++;

    if (expandIfNeeded()) {
      put(table, newEntry);
    } else if (prev != null) {
      prev.next = newEntry;
    } else {
      table[index] = newEntry;
    }

    file.incrementLinkCount();
    return newEntry;
  }

  private boolean expandIfNeeded() {
    if (loadFactor() > 0.8) {
      DirectoryEntry[] newTable = new DirectoryEntry[table.length * 2];

      for (DirectoryEntry entry : table) {
        for (; entry != null; entry = entry.next) {
          put(newTable, entry);
        }
      }

      this.table = newTable;
      return true;
    }

    return false;
  }

  private static void put(DirectoryEntry[] table, DirectoryEntry entry) {
    int index = indexOf(entry.name(), table.length);
    DirectoryEntry prev = null;
    DirectoryEntry existing = table[index];
    while (existing != null) {
      prev = existing;
      existing = existing.next;
    }

    if (prev != null) {
      prev.next = entry;
    } else {
      table[index] = entry;
    }
  }

  /**
   * Removes the entry for the given name from this table, returning that entry or {@code null} if
   * no such entry exists.
   */
  private DirectoryEntry remove(Name name) {
    int index = indexOf(name, table.length);

    DirectoryEntry prev = null;
    DirectoryEntry entry = table[index];
    while (entry != null) {
      if (name.equals(entry.name())) {
        if (prev == null) {
          table[index] = entry.next;
        } else {
          prev.next = entry.next;
        }

        entry.next = null;
        size--;
        entry.file().decrementLinkCount();
        return entry;
      }

      prev = entry;
      entry = entry.next;
    }

    throw new IllegalArgumentException("no entry matching '" + name + "' in this directory");
  }

  /**
   * Returns an iterator over the entries in this table.
   */
  @Override
  public Iterator<DirectoryEntry> iterator() {
    return new AbstractIterator<DirectoryEntry>() {
      private int index;
      @Nullable
      private DirectoryEntry entry;

      @Override
      protected DirectoryEntry computeNext() {
        if (entry != null) {
          entry = entry.next;
        }

        while (entry == null && index < table.length) {
          entry = table[index++];
        }

        return entry != null ? entry : endOfData();
      }
    };
  }

  private static Name checkValidName(Name name, String action) {
    if (isReserved(name)) {
      throw new IllegalArgumentException("cannot " + action + ": " + name);
    }
    return name;
  }

  private static boolean isReserved(Name name) {
    String string = name.toString();
    int length = string.length();
    if (length == 0 || length > 2) {
      return false;
    }

    for (int i = 0; i < string.length(); i++) {
      if (string.charAt(i) != '.') {
        return false;
      }
    }

    return true;
  }
}
