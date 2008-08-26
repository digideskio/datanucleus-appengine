// Copyright 2008 Google Inc. All Rights Reserved.
package org.datanucleus.store.appengine;

import com.google.apphosting.api.datastore.Entity;
import com.google.apphosting.api.datastore.EntityNotFoundException;
import com.google.apphosting.api.datastore.Key;
import com.google.apphosting.api.datastore.KeyFactory;

import static org.datanucleus.store.appengine.DatastorePersistenceHandler.DEFAULT_VERSION_PROPERTY_NAME;
import org.datanucleus.test.Book;
import org.datanucleus.test.HasVersionJPA;

import javax.persistence.EntityTransaction;
import javax.persistence.OptimisticLockException;
import javax.persistence.RollbackException;

/**
 * @author Erick Armbrust <earmbrust@google.com>
 */
public class JPAUpdateTest extends JPATestCase {

  public void testSimpleUpdate() throws EntityNotFoundException {
    Key key = ldth.ds.put(Book.newBookEntity("jimmy", "12345", "the title"));

    String keyStr = KeyFactory.encodeKey(key);
    Book book = em.find(Book.class, keyStr);
    EntityTransaction tx = em.getTransaction();

    assertEquals(keyStr, book.getId());
    assertEquals("jimmy", book.getAuthor());
    assertEquals("12345", book.getIsbn());
    assertEquals("the title", book.getTitle());

    tx.begin();
    book.setIsbn("56789");
    tx.commit();

    Entity bookCheck = ldth.ds.get(key);
    assertEquals("jimmy", bookCheck.getProperty("author"));
    assertEquals("56789", bookCheck.getProperty("isbn"));
    assertEquals("the title", bookCheck.getProperty("title"));
  }

  public void testOptimisticLocking_Update() {
    Entity entity = new Entity(HasVersionJPA.class.getName());
    entity.setProperty(DEFAULT_VERSION_PROPERTY_NAME, 1L);
    Key key = ldth.ds.put(entity);

    String keyStr = KeyFactory.encodeKey(key);
    HasVersionJPA hv = em.find(HasVersionJPA.class, keyStr);

    EntityTransaction tx = em.getTransaction();
    tx.begin();
    hv.setValue("value");
    tx.commit();
    assertEquals(2L, hv.getVersion());
    // make sure the version gets bumped
    entity.setProperty(DEFAULT_VERSION_PROPERTY_NAME, 3L);

    hv = em.find(HasVersionJPA.class, keyStr);
    tx = em.getTransaction();
    tx.begin();
    hv.setValue("another value");
    // we update the entity directly in the datastore right before commit
    entity.setProperty(DEFAULT_VERSION_PROPERTY_NAME, 7L);
    ldth.ds.put(entity);
    try {
      tx.commit();
      fail("expected optimistic exception");
    } catch (RollbackException re) {
      // good
      assertTrue(re.getCause() instanceof OptimisticLockException);
    }
    // make sure the version didn't change on the model object
    assertEquals(2L, hv.getVersion());
  }

  public void testOptimisticLocking_Delete() {
    Entity entity = new Entity(HasVersionJPA.class.getName());
    entity.setProperty(DEFAULT_VERSION_PROPERTY_NAME, 1L);
    Key key = ldth.ds.put(entity);

    String keyStr = KeyFactory.encodeKey(key);
    HasVersionJPA hv = em.find(HasVersionJPA.class, keyStr);

    EntityTransaction tx = em.getTransaction();
    tx.begin();
    // delete the entity in the datastore right before we commit
    ldth.ds.delete(key);
    hv.setValue("value");
    try {
      tx.commit();
      fail("expected optimistic exception");
    } catch (RollbackException re) {
      // good
      assertTrue(re.getCause() instanceof OptimisticLockException);
    }
    // make sure the version didn't change on the model object
    assertEquals(1L, hv.getVersion());
  }

}
