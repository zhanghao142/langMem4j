/**
 * MemoryStore SPI: storage-agnostic contract for upsert, lookup, search and
 * deletion of memories by namespace. Implementations live in sibling packages
 * ({@code inmemory}) or in dedicated adapter modules (e.g. qdrant).
 */
package com.langmem4j.core.store;
