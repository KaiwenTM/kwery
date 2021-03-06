/*
 * Copyright (c) 2015 Andrew O'Malley
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.github.andrewoma.kwery.mappertest.example

import com.github.andrewoma.kwery.mapper.listener.*
import com.github.andrewoma.kwery.mappertest.AbstractSessionTest
import com.github.andrewoma.kwery.mappertest.example.test.initialiseFilmSchema
import org.junit.Test
import kotlin.properties.Delegates
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DaoListenerPostCommitTest : AbstractSessionTest() {
    var dao: ActorDao by Delegates.notNull()
    val cache = hashMapOf<Int, Actor>()

    override var startTransactionByDefault = false

    override fun afterSessionSetup() {
        initialise("filmSchema") { initialiseFilmSchema(it) }
        super.afterSessionSetup()

        dao = ActorDao(session, FilmActorDao(session))
        dao.addListener(CacheListener())
        cache.clear()
        session.update("delete from actor where actor_id > -1000")
    }

    @Test fun `Values should only be visible after commit`() {
        session.transaction {
            dao.insert(Actor(Name("Bruce", "Lee")))
            assertTrue(cache.isEmpty())
        }
        assertEquals(1, cache.size)
    }

    @Test fun `Values should not be visible after roll back`() {
        session.transaction {
            dao.insert(Actor(Name("Bruce", "Lee")))
            assertTrue(cache.isEmpty())
            session.currentTransaction?.rollbackOnly = true
        }
        assertEquals(0, cache.size)
    }

    @Test fun `Updates should be visible after commit`() {
        val tommy = Name("Tommy", "Lee")
        session.transaction {
            val inserted = dao.insert(Actor(Name("Bruce", "Lee")))
            dao.update(inserted, inserted.copy(name = tommy))
            assertTrue(cache.isEmpty())
        }
        assertEquals(1, cache.size)
        assertEquals(tommy, cache.values.first().name)
    }

    @Test fun `Unsafe updates should be visible after commit`() {
        val tommy = Name("Tommy", "Lee")
        session.transaction {
            val inserted = dao.insert(Actor(Name("Bruce", "Lee")))
            dao.unsafeUpdate(inserted.copy(name = tommy))
            assertTrue(cache.isEmpty())
        }
        assertEquals(1, cache.size)
        assertEquals(tommy, cache.values.first().name)
    }

    @Test fun `Multiple transactions should accumulate`() {
        val tommy = Name("Tommy", "Lee")
        val inserted = session.transaction {
            dao.insert(Actor(Name("Bruce", "Lee")))
        }
        assertEquals(1, cache.size)

        val updated = session.transaction {
            dao.update(inserted, inserted.copy(name = tommy))
        }
        assertEquals(1, cache.size)
        assertEquals(tommy, cache.values.first().name)

        val deleted = session.transaction {
            dao.delete(updated.id)
        }
        assertEquals(1, deleted)
        assertTrue(cache.isEmpty())
    }

    @Test fun `Batch insert should be visible after commit`() {
        val inserted = session.transaction {
            val actors = listOf(
                    Actor(Name("Bruce", "Lee")),
                    Actor(Name("John", "Wayne"))
            )
            val result = dao.batchInsert(actors)
            assertTrue(cache.isEmpty())
            result
        }
        assertEquals(2, cache.size)
        assertEquals(inserted.toSet(), cache.values.toSet())
    }

    @Test fun `Batch update should be visible after commit`() {
        val tommy = Name("Tommy", "Lee")
        session.transaction {
            val actors = listOf(
                    Actor(Name("Bruce", "Lee")),
                    Actor(Name("John", "Wayne"))
            )
            val inserted = dao.batchInsert(actors)
            dao.batchUpdate(inserted.zip(inserted.map { it.copy(name = tommy) }))
            assertTrue(cache.isEmpty())
        }
        assertEquals(2, cache.size)
        cache.values.forEach { assertEquals(tommy, it.name) }
    }

    inner class CacheListener : DeferredListener() {
        override fun onCommit(committed: Boolean, events: List<Event>) {
            println("Post commit invoked")
            if (!committed) return // Don't update unless committed

            for (event in events) {
                println(event)
                // In the real world, the type would probably be used to lookup the cache
                assertEquals(Actor::class.java, event.table.type)
                when (event) {
                    is InsertEvent -> cache.put(event.id as Int, event.value as Actor)
                    is UpdateEvent -> cache.put(event.id as Int, event.new as Actor)
                    is DeleteEvent -> cache.remove(event.id as Int)
                }
            }
        }
    }
}

