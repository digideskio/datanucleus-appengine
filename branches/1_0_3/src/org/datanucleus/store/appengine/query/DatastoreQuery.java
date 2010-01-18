/**********************************************************************
Copyright (c) 2009 Google Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
**********************************************************************/
package org.datanucleus.store.appengine.query;

import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.FetchOptions;
import static com.google.appengine.api.datastore.FetchOptions.Builder.withLimit;
import static com.google.appengine.api.datastore.FetchOptions.Builder.withOffset;
import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.KeyFactory;
import com.google.appengine.api.datastore.PreparedQuery;
import com.google.appengine.api.datastore.Query;
import com.google.appengine.api.datastore.ShortBlob;
import com.google.appengine.api.datastore.Transaction;

import org.datanucleus.ClassLoaderResolver;
import org.datanucleus.FetchPlan;
import org.datanucleus.ManagedConnection;
import org.datanucleus.ManagedConnectionResourceListener;
import org.datanucleus.ObjectManager;
import org.datanucleus.StateManager;
import org.datanucleus.api.ApiAdapter;
import org.datanucleus.exceptions.NucleusException;
import org.datanucleus.exceptions.NucleusUserException;
import org.datanucleus.metadata.AbstractClassMetaData;
import org.datanucleus.metadata.AbstractMemberMetaData;
import org.datanucleus.metadata.EmbeddedMetaData;
import org.datanucleus.metadata.MetaDataManager;
import org.datanucleus.query.compiler.QueryCompilation;
import org.datanucleus.query.expression.DyadicExpression;
import org.datanucleus.query.expression.Expression;
import org.datanucleus.query.expression.InvokeExpression;
import org.datanucleus.query.expression.JoinExpression;
import org.datanucleus.query.expression.Literal;
import org.datanucleus.query.expression.OrderExpression;
import org.datanucleus.query.expression.ParameterExpression;
import org.datanucleus.query.expression.PrimaryExpression;
import org.datanucleus.store.FieldValues;
import org.datanucleus.store.appengine.DatastoreFieldManager;
import org.datanucleus.store.appengine.DatastoreManager;
import org.datanucleus.store.appengine.DatastorePersistenceHandler;
import org.datanucleus.store.appengine.DatastoreServiceFactoryInternal;
import org.datanucleus.store.appengine.DatastoreTable;
import org.datanucleus.store.appengine.DatastoreTransaction;
import org.datanucleus.store.appengine.EntityUtils;
import org.datanucleus.store.appengine.PrimitiveArrays;
import org.datanucleus.store.appengine.Utils;
import org.datanucleus.store.appengine.Utils.Function;
import org.datanucleus.store.mapped.IdentifierFactory;
import org.datanucleus.store.mapped.mapping.JavaTypeMapping;
import org.datanucleus.store.mapped.mapping.PersistenceCapableMapping;
import org.datanucleus.store.query.AbstractJavaQuery;
import org.datanucleus.util.Localiser;
import org.datanucleus.util.NucleusLogger;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.jdo.spi.PersistenceCapable;

/**
 * A unified JDOQL/JPQL query implementation for Datastore.
 *
 * Datanucleus supports in-memory evaluation of queries, but
 * for now we have it disabled and are only allowing queries
 * that can be natively fulfilled by the app engine datastore.
 *
 * TODO(maxr): More logging
 * TODO(maxr): Localized logging
 * TODO(maxr): Localized exception messages.
 *
 * @author Erick Armbrust <earmbrust@google.com>
 * @author Max Ross <maxr@google.com>
 */
public class DatastoreQuery implements Serializable {

  // Exposed for testing
  static final Expression.Operator GROUP_BY_OP = new Expression.Operator(
      "GROUP BY", Integer.MAX_VALUE);

  // Exposed for testing
  static final Expression.Operator HAVING_OP = new Expression.Operator(
      "HAVING", Integer.MAX_VALUE);

  // Exposed for testing
  static final Expression.Operator JOIN_OP = new Expression.Operator(
      "JOIN", Integer.MAX_VALUE);

  static final Set<Expression.Operator> UNSUPPORTED_OPERATORS =
      Utils.newHashSet((Expression.Operator) Expression.OP_ADD,
          (Expression.Operator) Expression.OP_COM,
          (Expression.Operator) Expression.OP_CONCAT,
          (Expression.Operator) Expression.OP_DIV,
          (Expression.Operator) Expression.OP_IS,
          (Expression.Operator) Expression.OP_ISNOT,
          (Expression.Operator) Expression.OP_LIKE,
          (Expression.Operator) Expression.OP_MOD,
          (Expression.Operator) Expression.OP_NEG,
          (Expression.Operator) Expression.OP_MUL,
          (Expression.Operator) Expression.OP_NOT,
          (Expression.Operator) Expression.OP_OR,
          (Expression.Operator) Expression.OP_SUB);

  private static final
  Map<Expression.Operator, Query.FilterOperator> DATANUCLEUS_OP_TO_APPENGINE_OP = buildNewOpMap();

  private static Map<Expression.Operator, Query.FilterOperator> buildNewOpMap() {
    Map<Expression.Operator, Query.FilterOperator> map =
        new HashMap<Expression.Operator, Query.FilterOperator>();
    map.put(Expression.OP_EQ, Query.FilterOperator.EQUAL);
    map.put(Expression.OP_GT, Query.FilterOperator.GREATER_THAN);
    map.put(Expression.OP_GTEQ, Query.FilterOperator.GREATER_THAN_OR_EQUAL);
    map.put(Expression.OP_LT, Query.FilterOperator.LESS_THAN);
    map.put(Expression.OP_LTEQ, Query.FilterOperator.LESS_THAN_OR_EQUAL);
    // only supported when the rhs is 'null'
    map.put(Expression.OP_NOTEQ, Query.FilterOperator.GREATER_THAN);
    return map;
  }

  /**
   * The query that is generated by Datanucleus.
   */
  private final AbstractJavaQuery query;

  /**
   * The current datastore query.
   */
  private transient Query latestDatastoreQuery;

  private boolean isBulkDelete() {
    return query.getType() == org.datanucleus.store.query.Query.BULK_DELETE;
  }

  /**
   * The different types of datastore query results that we support.
   */
  private enum ResultType {
    ENTITY, // return entities
    ENTITY_PROJECTION, // return specific fields of an entity
    COUNT,  // return the count
    KEYS_ONLY // return just the keys
  }

  /**
   * Constructs a new Datastore query based on a Datanucleus query.
   *
   * @param query The Datanucleus query to be translated into a Datastore query.
   */
  public DatastoreQuery(AbstractJavaQuery query) {
    this.query = query;
  }

  /**
   * We'd like to return {@link Iterable} instead but
   * {@link javax.persistence.Query#getResultList()} returns {@link List}.
   *
   * @param localiser The localiser to use.
   * @param compilation The compiled query.
   * @param fromInclNo The index of the first result the user wants returned.
   * @param toExclNo The index of the last result the user wants returned.
   * @param parameters Parameter values for the query.
   *
   * @return The result of executing the query.
   */
  public Object performExecute(Localiser localiser, QueryCompilation compilation,
      long fromInclNo, long toExclNo, Map<String, ?> parameters) {
    ObjectManager om = getObjectManager();
    DatastoreManager storeMgr = getStoreManager();
    ClassLoaderResolver clr = om.getClassLoaderResolver();
    AbstractClassMetaData acmd = getMetaDataManager().getMetaDataForClass(query.getCandidateClass(), clr);
    if (acmd == null) {
      throw new NucleusUserException("No meta data for " + query.getCandidateClass().getName()
          + ".  Perhaps you need to run the enhancer on this class?").setFatal();
    }

    storeMgr.validateMetaDataForClass(acmd, clr);

    DatastoreTable table = storeMgr.getDatastoreClass(acmd.getFullClassName(), clr);
    QueryData qd = validate(compilation, parameters, acmd, table, clr);

    if (NucleusLogger.QUERY.isDebugEnabled()) {
      NucleusLogger.QUERY.debug(localiser.msg("021046", "DATASTORE", query.getSingleStringQuery(), null));
    }

    if (toExclNo == 0 ||
        (rangeValueIsSet(toExclNo)
            && rangeValueIsSet(fromInclNo)
            && (toExclNo - fromInclNo) <= 0)) {
      // short-circuit - no point in executing the query
      return Collections.emptyList();
    }
    addFilters(qd);
    addSorts(qd);
    DatastoreService ds = DatastoreServiceFactoryInternal.getDatastoreService();
    // Txns don't get started until you allocate a connection, so allocate a
    // connection before we do anything that might require a txn.
    ManagedConnection mconn = getStoreManager().getConnection(getObjectManager());
    try {
      if (qd.batchGetKeys != null) {
        return fulfillBatchGetQuery(ds, qd, mconn);
      } else {
        latestDatastoreQuery = qd.datastoreQuery;
        Transaction txn = null;
        Map extensions = query.getExtensions();
        // give users a chance to opt-out of having their query execute in a txn
        if (extensions == null ||
            !extensions.containsKey(DatastoreManager.EXCLUDE_QUERY_FROM_TXN) ||
            !(Boolean)extensions.get(DatastoreManager.EXCLUDE_QUERY_FROM_TXN)) {
          // If this is an ancestor query, execute it in the current transaction
          txn = qd.datastoreQuery.getAncestor() != null ? ds.getCurrentTransaction(null) : null;
        }
        PreparedQuery preparedQuery = ds.prepare(txn, qd.datastoreQuery);
        FetchOptions opts = buildFetchOptions(fromInclNo, toExclNo);
        if (qd.resultType == ResultType.COUNT) {
          return fulfillCountQuery(preparedQuery, opts);
        } else {
          if (qd.resultType == ResultType.KEYS_ONLY || isBulkDelete()) {
            qd.datastoreQuery.setKeysOnly();
          }
          return fulfillEntityQuery(preparedQuery, opts, qd.resultTransformer, ds, mconn);
        }
      }
    } finally {
      mconn.release();
    }
  }

  private Object fulfillBatchGetQuery(DatastoreService ds, QueryData qd, ManagedConnection mconn) {
    DatastoreTransaction txn = EntityUtils.getCurrentTransaction(getObjectManager());
    Transaction innerTxn = txn == null ? null : txn.getInnerTxn();
    if (isBulkDelete()) {
      return fulfillBatchDeleteQuery(innerTxn, ds, qd);
    } else {
      Collection<Entity> entities = ds.get(innerTxn, qd.batchGetKeys).values();
      if (qd.resultType == ResultType.COUNT) {
        return Collections.singletonList(entities.size());
      }
      return newStreamingQueryResultForEntities(entities, qd.resultTransformer, mconn);
    }
  }

  private long fulfillBatchDeleteQuery(Transaction innerTxn, DatastoreService ds, QueryData qd) {
    Set<Key> keysToDelete = qd.batchGetKeys;
    Map extensions = query.getExtensions();
    if (extensions != null &&
        extensions.containsKey(DatastoreManager.SLOW_BUT_MORE_ACCURATE_JPQL_DELETE_QUERY) &&
        (Boolean)extensions.get(DatastoreManager.SLOW_BUT_MORE_ACCURATE_JPQL_DELETE_QUERY)) {
      Map<Key, Entity> getResult = ds.get(innerTxn, qd.batchGetKeys);
      keysToDelete = getResult.keySet();
    }
    // The datastore doesn't give any indication of how many entities were
    // actually deleted, so by default we just return the number of keys
    // that we were asked to delete.  If the "slow-but-more-accurate" extension
    // is set for the query we'll first fetch the entities identified by the
    // keys and then delete whatever is returned.  This is more accurate but
    // not guaranteed accurate, since if we're executing without a txn,
    // something could get deleted in between the fetch and the delete.
    ds.delete(innerTxn, keysToDelete);
    return (long) keysToDelete.size();
  }

  private List<Integer> fulfillCountQuery(PreparedQuery preparedQuery, FetchOptions opts) {
    if (opts != null) {
      // TODO(maxr) support count + offset/limit by issuing a non-count
      // query and returning the size of the result set
      throw new UnsupportedOperationException(
          "The datastore does not support using count() in conjunction with offset and/or "
          + "limit.  You can get the answer to this query by issuing the query without "
          + "count() and then counting the size of the result set.");
    }

    return Collections.singletonList(preparedQuery.countEntities());
  }

  private Object fulfillEntityQuery(
      PreparedQuery preparedQuery, FetchOptions opts, Function<Entity, Object> resultTransformer,
      DatastoreService ds, ManagedConnection mconn) {
    Iterable<Entity> entities;
    if (opts != null) {
      entities = preparedQuery.asIterable(opts);
    } else {
      entities = preparedQuery.asIterable();
    }

    if (isBulkDelete()) {
      return deleteEntityQueryResult(entities, ds);
    }
    return newStreamingQueryResultForEntities(entities, resultTransformer, mconn);
  }

  private long deleteEntityQueryResult(Iterable<Entity> entities, DatastoreService ds) {
    List<Key> keysToDelete = Utils.newArrayList();
    for (Entity e : entities) {
      keysToDelete.add(e.getKey());
    }
    ds.delete(ds.getCurrentTransaction(null), keysToDelete);
    return (long) keysToDelete.size();
  }

  private List<?> newStreamingQueryResultForEntities(
      Iterable<Entity> entities, final Function<Entity, Object> resultTransformer, final ManagedConnection mconn) {
    final StreamingQueryResult qr = new StreamingQueryResult(
        query, new RuntimeExceptionWrappingIterable(entities), resultTransformer);
    // Add a listener to the connection so we can get a callback when the connection is
    // flushed.
    ManagedConnectionResourceListener listener = new ManagedConnectionResourceListener() {
      public void managedConnectionPreClose() {}
      public void managedConnectionPostClose() {}
      public void managedConnectionFlushed() {
        // Disconnect the query from this ManagedConnection (read in unread rows etc)
        qr.disconnect();
      }

      public void resourcePostClose() {
        mconn.removeListener(this);
      }
    };
    mconn.addListener(listener);
    qr.addConnectionListener(listener);
    return qr;
  }

  /**
   * Datanucleus provides {@link Long#MAX_VALUE} if the range value was not set
   * by the user.
   */
  private boolean rangeValueIsSet(long rangeVal) {
    return rangeVal != Long.MAX_VALUE;
  }

  /**
   * Build a FetchOptions instance using the provided params.
   * @return A FetchOptions instance built using the provided params,
   * or {@code null} if neither param is set.
   */
  FetchOptions buildFetchOptions(long fromInclNo, long toExclNo) {
    FetchOptions opts = null;
    Integer offset = null;
    if (fromInclNo != 0 && rangeValueIsSet(fromInclNo)) {
      // datastore api expects an int because we cap you at 1000 anyway.
      offset = (int) Math.min(Integer.MAX_VALUE, fromInclNo);
      opts = withOffset(offset);
    }
    if (rangeValueIsSet(toExclNo)) {
      // datastore api expects an int because we cap you at 1000 anyway.
      int intExclNo = (int) Math.min(Integer.MAX_VALUE, toExclNo);
      if (opts == null) {
        // When fromInclNo isn't specified, intExclNo (the index of the last
        // result to return) and limit are the same.
        opts = withLimit(intExclNo);
      } else {
        // When we have values for both fromInclNo and toExclNo
        // we can't take toExclNo as the limit for the query because
        // toExclNo is the index of the last result, not the max
        // results to return.  In this scenario the limit is the
        // index of the last result minus the offset.  For example, if
        // fromInclNo is 10 and toExclNo is 25, the limit for the query
        // is 15 because we want 15 results starting after the first 10.

        // We know that offset won't be null because opts is not null.
        opts.limit(intExclNo - offset);
      }
    }
    return opts;
  }

  private Object entityToPojo(Entity entity, AbstractClassMetaData acmd,
      ClassLoaderResolver clr, DatastoreManager storeMgr, FetchPlan fp) {
    return entityToPojo(entity, acmd, clr, storeMgr, getObjectManager(), query.getIgnoreCache(), fp);
  }

  /**
   * Converts the provided entity to a pojo.
   *
   * @param entity The entity to convert
   * @param acmd The meta data for the pojo class
   * @param clr The classloader resolver
   * @param storeMgr The store manager
   * @param om The object manager
   * @param ignoreCache Whether or not the cache should be ignored when the
   * object manager attempts to find the pojo
   * @return The pojo that corresponds to the provided entity.
   */
  public static Object entityToPojo(final Entity entity, final AbstractClassMetaData acmd,
      final ClassLoaderResolver clr, final DatastoreManager storeMgr, ObjectManager om,
      boolean ignoreCache, final FetchPlan fetchPlan) {
    storeMgr.validateMetaDataForClass(acmd, clr);
    FieldValues fv = new FieldValues() {
      public void fetchFields(StateManager sm) {
        sm.replaceFields(
            acmd.getPKMemberPositions(), new DatastoreFieldManager(sm, storeMgr, entity));
      }
      public void fetchNonLoadedFields(StateManager sm) {
        sm.replaceNonLoadedFields(
            acmd.getPKMemberPositions(), new DatastoreFieldManager(sm, storeMgr, entity));
      }
      public FetchPlan getFetchPlanForLoading() {
        return fetchPlan;
      }
    };
    Object pojo = om.findObjectUsingAID(clr.classForName(acmd.getFullClassName()), fv, ignoreCache, true);
    StateManager stateMgr = om.findStateManager(pojo);
    DatastorePersistenceHandler handler = storeMgr.getPersistenceHandler();
    // TODO(maxr): Seems like we should be able to refactor the handler
    // so that we can do a fetch without having to hide the entity in the
    // state manager.
    handler.setAssociatedEntity(stateMgr, EntityUtils.getCurrentTransaction(om), entity);
    int[] fieldsToFetch =
        fetchPlan != null ?
        fetchPlan.getFetchPlanForClass(acmd).getFieldsInActualFetchPlan() : acmd.getAllMemberPositions();
    storeMgr.getPersistenceHandler().fetchObject(
        stateMgr, fieldsToFetch);
    return pojo;
  }

  /**
   * Converts the provided entity to its pojo primary key representation.
   *
   * @param entity The entity to convert
   * @param acmd The meta data for the pojo class
   * @param clr The classloader resolver
   * @param storeMgr The store manager
   * @param om The object manager
   * @return The pojo that corresponds to the id of the provided entity.
   */
  private static Object entityToPojoPrimaryKey(final Entity entity, final AbstractClassMetaData acmd,
      ClassLoaderResolver clr, final DatastoreManager storeMgr, ObjectManager om) {
    storeMgr.validateMetaDataForClass(acmd, clr);
    FieldValues fv = new FieldValues() {
      public void fetchFields(StateManager sm) {
        sm.replaceFields(
            acmd.getPKMemberPositions(), new DatastoreFieldManager(sm, storeMgr, entity));
      }
      public void fetchNonLoadedFields(StateManager sm) {
      }
      public FetchPlan getFetchPlanForLoading() {
        return null;
      }
    };
    return om.findObjectUsingAID(clr.classForName(acmd.getFullClassName()), fv, false, true);
  }

  private QueryData validate(QueryCompilation compilation, Map<String, ?> parameters,
                             final AbstractClassMetaData acmd, DatastoreTable table,
                             final ClassLoaderResolver clr) {
    if (query.getType() == org.datanucleus.store.query.Query.BULK_UPDATE) {
      throw new NucleusUserException("Only select and delete statements are supported.").setFatal();
    }

    if (query.getCandidateClass() == null) {
      throw new NucleusUserException(
          "Candidate class could not be found: " + query.getSingleStringQuery()).setFatal();
    }
    // We don't support in-memory query fulfillment, so if the query contains
    // a grouping or a having it's automatically an error.
    if (query.getGrouping() != null) {
      throw new UnsupportedDatastoreOperatorException(query.getSingleStringQuery(),
          GROUP_BY_OP);
    }

    if (query.getHaving() != null) {
      throw new UnsupportedDatastoreOperatorException(query.getSingleStringQuery(),
          HAVING_OP);
    }

    if (compilation.getExprFrom() != null) {
      for (Expression fromExpr : compilation.getExprFrom()) {
        checkNotJoin(fromExpr);
      }
    }

    final List<AbstractMemberMetaData> projectionFields = Utils.newArrayList();
    ResultType resultType = validateResultExpression(compilation, acmd, projectionFields);
    // TODO(maxr): Add checks for subqueries and anything else we don't allow
    String kind = getIdentifierFactory().newDatastoreContainerIdentifier(acmd).getIdentifierName();
    Function<Entity, Object> resultTransformer;
    if (resultType == ResultType.KEYS_ONLY) {
      resultTransformer = new Function<Entity, Object>() {
        public Object apply(Entity from) {
          return entityToPojoPrimaryKey(from, acmd, clr, getDatastoreManager(), getObjectManager());
        }
      };
    } else {
      resultTransformer = new Function<Entity, Object>() {
        public Object apply(Entity from) {
          FetchPlan fp = query.getFetchPlan();
          if (!projectionFields.isEmpty()) {
            // If this is a projection, ignore the fetch plan and just fetch everything.
            // We do this because we're returning individual fields, not an entire
            // entity.
            fp = null;
          }
          return entityToPojo(from, acmd, clr, getDatastoreManager(), fp);
        }
      };
    }

    if (!projectionFields.isEmpty()) {
      // Wrap the existing transformer with a transformer that will apply the
      // appropriate projection to each Entity in the result set.
      resultTransformer = new ProjectionResultTransformer(resultTransformer, getObjectManager(),
                                                          projectionFields, query.getSingleStringQuery());
    }
    return new QueryData(
        parameters, acmd, table, compilation, new Query(kind), resultType, resultTransformer);
  }

  /**
   * @param compilation The compiled query
   * @param acmd The meta data for the class we're querying
   * @param projectionFields Out param that will contain the member meta-data
   * of any fields that have been explicitly selected in the result
   * expression.
   *
   * @return The ResultType
   */
  private ResultType validateResultExpression(
      QueryCompilation compilation, AbstractClassMetaData acmd, List<AbstractMemberMetaData> projectionFields) {
    ResultType resultType = null;
    if (compilation.getExprResult() != null) {
      // the only expression results we support are count() and PrimaryExpression
      for (Expression resultExpr : compilation.getExprResult()) {
        if (resultExpr instanceof InvokeExpression) {
          InvokeExpression invokeExpr = (InvokeExpression) resultExpr;
          if (!invokeExpr.getOperation().equals("count")) {
            Expression.Operator operator = new Expression.Operator(invokeExpr.getOperation(), 0);
            throw new UnsupportedDatastoreOperatorException(query.getSingleStringQuery(), operator);
          } else if (!projectionFields.isEmpty()) {
            throw newAggregateAndRowResultsException();
          } else {
            resultType = ResultType.COUNT;
          }
        } else if (resultExpr instanceof PrimaryExpression) {
          if (resultType == ResultType.COUNT) {
            throw newAggregateAndRowResultsException();            
          }
          if (resultType == null) {
            resultType = ResultType.KEYS_ONLY;
          }
          PrimaryExpression primaryExpr = (PrimaryExpression) resultExpr;
          if (!primaryExpr.getId().equals(compilation.getCandidateAlias())) {
            AbstractMemberMetaData ammd =
                getMemberMetaData(acmd, getTuples(primaryExpr, compilation.getCandidateAlias()));
            if (ammd == null) {
              throw noMetaDataException(primaryExpr.getId(), acmd.getFullClassName());
            }
            projectionFields.add(ammd);
            if (ammd.getParent() instanceof EmbeddedMetaData || !ammd.isPrimaryKey()) {
              // A single non-pk field locks the result type on entity projection
              resultType = ResultType.ENTITY_PROJECTION;
            }
          }
        } else {
          // We don't support any other result expressions
          Expression.Operator operator =
              new Expression.Operator(resultExpr.getClass().getName(), 0);
          throw new UnsupportedDatastoreOperatorException(query.getSingleStringQuery(), operator);
        }
      }
    }
    if (resultType == null) {
      resultType = ResultType.ENTITY;
    }
    return resultType;
  }

  private UnsupportedDatastoreFeatureException newAggregateAndRowResultsException() {
    // We don't let you combine aggregate functions with requests
    // for specific fields in the result expression.  hsqldb has the
    // same restriction so I feel ok about this
    return new UnsupportedDatastoreFeatureException(
        "Cannot combine an aggregate results with row results.", query.getSingleStringQuery());
  }


  private void checkNotJoin(Expression expr) {
    if (expr instanceof JoinExpression) {
      throw new UnsupportedDatastoreFeatureException("Cannot fulfill queries with joins.",
          query.getSingleStringQuery());
    }
    if (expr.getLeft() != null) {
      checkNotJoin(expr.getLeft());
    }
    if (expr.getRight() != null) {
      checkNotJoin(expr.getRight());
    }
  }

  /**
   * Adds sorts to the given {@link Query} by examining the compiled order
   * expression.
   */
  private void addSorts(QueryData qd) {
    Expression[] orderBys = qd.compilation.getExprOrdering();
    if (orderBys == null) {
      return;
    }
    for (Expression expr : orderBys) {
      OrderExpression oe = (OrderExpression) expr;
      Query.SortDirection dir = oe.getSortOrder() == null || oe.getSortOrder().equals("ascending")
              ? Query.SortDirection.ASCENDING : Query.SortDirection.DESCENDING;
      PrimaryExpression left = (PrimaryExpression) oe.getLeft();
      AbstractMemberMetaData ammd =
          getMemberMetaData(qd.acmd, getTuples(left, qd.compilation.getCandidateAlias()));
      if (ammd == null) {
        throw noMetaDataException(left.getId(), qd.acmd.getFullClassName());
      }
      if (isParentPK(ammd)) {
        throw new UnsupportedDatastoreFeatureException(
            "Cannot sort by parent.", query.getSingleStringQuery());
      } else {
        String sortProp;
        if (ammd.isPrimaryKey()) {
          sortProp = Entity.KEY_RESERVED_PROPERTY;
        } else {
          sortProp = determinePropertyName(ammd);
        }
        if (qd.batchGetKeys != null) {
          // Can't have any sort orders if doing a batch get
          throwInvalidBatchLookupException();
        }
        qd.datastoreQuery.addSort(sortProp, dir);
      }
    }
  }

  IdentifierFactory getIdentifierFactory() {
    return getStoreManager().getIdentifierFactory();
  }

  private DatastoreManager getStoreManager() {
    return (DatastoreManager) getObjectManager().getStoreManager();
  }

  /**
   * Adds filters to the given {@link Query} by examining the compiled filter
   * expression.
   */
  private void addFilters(QueryData qd) {
    Expression filter = qd.compilation.getExprFilter();
    addExpression(filter, qd);
  }

  /**
   * Struct used to represent info about the query we need to fulfill.
   */
  private static final class QueryData {
    private final Map parameters;
    private final AbstractClassMetaData acmd;
    private final Map<String, DatastoreTable> tableMap = Utils.newHashMap();
    private final QueryCompilation compilation;
    private final Query datastoreQuery;
    private final ResultType resultType;
    private final Function<Entity, Object> resultTransformer;
    private Set<Key> batchGetKeys;

    private QueryData(
        Map parameters, AbstractClassMetaData acmd, DatastoreTable table,
        QueryCompilation compilation, Query datastoreQuery, ResultType resultType,
        Function<Entity, Object> resultTransformer) {
      this.parameters = parameters;
      this.acmd = acmd;
      this.tableMap.put(acmd.getFullClassName(), table);
      this.compilation = compilation;
      this.datastoreQuery = datastoreQuery;
      this.resultType = resultType;
      this.resultTransformer = resultTransformer;
    }
  }

  /**
   * Recursively walks the given expression, adding filters to the given
   * {@link Query} where appropriate.
   *
   * @throws UnsupportedDatastoreOperatorException If we encounter an operator
   *           that we don't support.
   * @throws UnsupportedDatastoreFeatureException If the query uses a feature
   *           that we don't support.
   */
  private void addExpression(Expression expr, QueryData qd) {
    if (expr == null) {
      return;
    }
    checkForUnsupportedOperator(expr.getOperator());
    if (expr instanceof DyadicExpression) {
      if (expr.getOperator().equals(Expression.OP_AND)) {
        addExpression(expr.getLeft(), qd);
        addExpression(expr.getRight(), qd);
      } else if (DATANUCLEUS_OP_TO_APPENGINE_OP.get(expr.getOperator()) == null) {
        throw new UnsupportedDatastoreOperatorException(query.getSingleStringQuery(),
            expr.getOperator());
      } else if (expr.getLeft() instanceof PrimaryExpression) {
        addLeftPrimaryExpression(
            (PrimaryExpression) expr.getLeft(), expr.getOperator(), expr.getRight(), qd);
      } else {
        // Recurse!
        addExpression(expr.getLeft(), qd);
        addExpression(expr.getRight(), qd);
      }
    } else if (expr instanceof PrimaryExpression) {
      // Recurse!
      addExpression(expr.getLeft(), qd);
      addExpression(expr.getRight(), qd);
    } else if (expr instanceof InvokeExpression) {
      InvokeExpression invocation = ((InvokeExpression) expr);
      if (invocation.getOperation().equals("contains") && invocation.getArguments().size() == 1) {
        handleContainsOperation(invocation, expr, qd);
      } else if (invocation.getOperation().equals("startsWith") && invocation.getArguments().size() == 1) {
        handleStartsWithOperation(invocation, expr, qd);
      } else if (invocation.getOperation().equals("matches") && invocation.getArguments().size() == 1) {
        handleMatchesOperation(invocation, expr, qd);
      } else {
        throw newUnsupportedQueryMethodException(invocation);
      }
    } else {
      throw new UnsupportedDatastoreFeatureException(
          "Unexpected expression type while parsing query: "
              + expr.getClass().getName(), query.getSingleStringQuery());
    }
  }

  private void handleMatchesOperation(InvokeExpression invocation, Expression expr,
                                      QueryData qd) {
    Expression param = (Expression) invocation.getArguments().get(0);
    if (expr.getLeft() instanceof PrimaryExpression && param instanceof Literal) {
      String matchesExpr = getPrefixFromMatchesExpression(((Literal) param).getLiteral());
      addPrefix((PrimaryExpression) expr.getLeft(), new Literal(matchesExpr), matchesExpr, qd);
    } else if (expr.getLeft() instanceof PrimaryExpression &&
               param instanceof ParameterExpression) {
      ParameterExpression parameterExpression = (ParameterExpression) param;
      Object parameterValue = getParameterValue(qd, parameterExpression);
      String matchesExpr = getPrefixFromMatchesExpression(parameterValue);
      addPrefix((PrimaryExpression) expr.getLeft(), new Literal(matchesExpr), matchesExpr, qd);
    } else {
      // We don't know what this is.
      throw newUnsupportedQueryMethodException(invocation);
    }
  }

  private String getPrefixFromMatchesExpression(Object matchesExprObj) {
    if (matchesExprObj instanceof Character) {
      matchesExprObj = matchesExprObj.toString();
    }
    if (!(matchesExprObj instanceof String)) {
      throw new NucleusUserException(
          "Prefix matching only supported on strings (received a "
          + matchesExprObj.getClass().getName() + ").").setFatal();
    }
    String matchesExpr = (String) matchesExprObj;
    int wildcardIndex = matchesExpr.indexOf('%');
    if (wildcardIndex != matchesExpr.length() - 1) {
      throw new UnsupportedDatastoreFeatureException(
          "Wildcard must appear at the end of the expression string (only prefix matches are supported)",
          query.getSingleStringQuery());
    }
    return matchesExpr.substring(0, wildcardIndex);
  }

  private void addPrefix(PrimaryExpression left, Expression right, String prefix, QueryData qd) {
    addLeftPrimaryExpression(left, Expression.OP_GTEQ, right, qd);
    Expression param = getUpperLimitForStartsWithStr(prefix);
    addLeftPrimaryExpression(left, Expression.OP_LT, param, qd);
  }

  /**
   * We fulfill startsWith by adding a >= filter for the method argument and a
   * < filter for the method argument translated into an upper limit for the
   * scan.
   */
  private void handleStartsWithOperation(InvokeExpression invocation, Expression expr,
                                         QueryData qd) {
    Expression param = (Expression) invocation.getArguments().get(0);
    param.bind();
    if (expr.getLeft() instanceof PrimaryExpression && param instanceof Literal) {
      addPrefix((PrimaryExpression) expr.getLeft(), param, (String) ((Literal) param).getLiteral(), qd);
    } else if (expr.getLeft() instanceof PrimaryExpression &&
               param instanceof ParameterExpression) {
      Object parameterValue = getParameterValue(qd, (ParameterExpression) param);
      addPrefix((PrimaryExpression) expr.getLeft(), param, (String) parameterValue, qd);
    } else {
      // We don't know what this is.
      throw newUnsupportedQueryMethodException(invocation);
    }
  }

  private void handleContainsOperation(InvokeExpression invocation, Expression expr, QueryData qd) {
    Expression param = (Expression) invocation.getArguments().get(0);
    param.bind();
    if (expr.getLeft() instanceof PrimaryExpression) {
      PrimaryExpression left = (PrimaryExpression) expr.getLeft();
      addLeftPrimaryExpression(left, Expression.OP_EQ, param, qd);
    } else if (expr.getLeft() instanceof ParameterExpression &&
               param instanceof PrimaryExpression) {
      ParameterExpression pe = (ParameterExpression) expr.getLeft();
      addLeftPrimaryExpression((PrimaryExpression) param, Expression.OP_EQ, pe, qd);
    } else {
      throw newUnsupportedQueryMethodException(invocation);
    }
  }

  /**
   * Converts a string like "ya" to "yb", but does so at the byte level to
   * model the actual behavior of the datastore.
   */
  private Literal getUpperLimitForStartsWithStr(String val) {
    byte[] bytes = val.getBytes();
    for (int i = bytes.length - 1; i >= 0; i--) {
      byte[] endKey = new byte[i + 1];
      System.arraycopy(bytes, 0, endKey, 0, i + 1);
      if (++endKey[i] != 0) {
        return new Literal(new String(endKey));
      }
    }
    return null;
  }

  private UnsupportedDatastoreFeatureException newUnsupportedQueryMethodException(
      InvokeExpression invocation) {
    throw new UnsupportedDatastoreFeatureException(
        "Unsupported method <" + invocation.getOperation() + "> while parsing expression: " + invocation,
        query.getSingleStringQuery());
  }

  private Object getParameterValue(QueryData qd, ParameterExpression pe) {
    if (pe.getPosition() != -1 &&
        qd.parameters != null &&
        qd.parameters.get(pe.getPosition()) != null) {
      // implicit param
      return qd.parameters.get(pe.getPosition());
    }
    return qd.parameters.get(pe.getId());
  }

  private void addLeftPrimaryExpression(PrimaryExpression left,
      Expression.Operator operator, Expression right, QueryData qd) {
    Query.FilterOperator op = DATANUCLEUS_OP_TO_APPENGINE_OP.get(operator);
    if (op == null) {
      throw new UnsupportedDatastoreFeatureException("Operator " + operator + " does not have a "
          + "corresponding operator in the datastore api.", query.getSingleStringQuery());
    }
    Object value;
    if (right instanceof PrimaryExpression) {
      value = qd.parameters.get(((PrimaryExpression) right).getId());
    } else if (right instanceof Literal) {
      value = ((Literal) right).getLiteral();
    } else if (right instanceof ParameterExpression) {
      value = getParameterValue(qd, (ParameterExpression) right);
    } else if (right instanceof DyadicExpression) {
      // In general we don't support nested dyadic expressions
      // but we special case negation:
      // select * from table where val = -33
      DyadicExpression dyadic = (DyadicExpression) right;
      if (dyadic.getLeft() instanceof Literal &&
          ((Literal) dyadic.getLeft()).getLiteral() instanceof Number &&
          dyadic.getRight() == null &&
          Expression.OP_NEG.equals(dyadic.getOperator())) {
        Number negateMe = (Number) ((Literal) dyadic.getLeft()).getLiteral();
        value = negateNumber(negateMe);
      } else {
        throw new UnsupportedDatastoreFeatureException(
            "Right side of expression is composed of unsupported components.  "
            + "Left: " + dyadic.getLeft().getClass().getName()
            + ", Op: " + dyadic.getOperator()
            + ", Right: " + dyadic.getRight(), query.getSingleStringQuery());
      }
    } else if (right instanceof InvokeExpression) {
      InvokeExpression invoke = (InvokeExpression) right;
      // can't support CURRENT_TIME because we don't have a Time meaning.
      // maybe we can store Time fields as int64 without the temporal meaning?
      if (invoke.getOperation().equals("CURRENT_TIMESTAMP") ||
          invoke.getOperation().equals("CURRENT_DATE")) {
        value = NOW_PROVIDER.now();
      } else {
        // We don't support any other InvokeExpressions right now but we can at least
        // give a better error.
        throw newUnsupportedQueryMethodException((InvokeExpression) right);
      }
    } else {
      throw new UnsupportedDatastoreFeatureException(
          "Right side of expression is of unexpected type: " + right.getClass().getName(),
          query.getSingleStringQuery());
    }
    // We can only support != null.
    if (operator.equals(Expression.OP_NOTEQ) && value != null) {
      throw new UnsupportedDatastoreOperatorException(
          query.getSingleStringQuery(), Expression.OP_NOTEQ,
          "The 'not equal' operator is only supported when the operator argument is 'null'");
    }
    List<String> tuples = getTuples(left, qd.compilation.getCandidateAlias());
    AbstractMemberMetaData ammd = getMemberMetaData(qd.acmd, tuples);
    if (ammd == null) {
      throw noMetaDataException(left.getId(), qd.acmd.getFullClassName());
    }
    JavaTypeMapping mapping = getMappingForFieldWithName(tuples, qd);
    if (mapping instanceof PersistenceCapableMapping) {
      processPersistenceCapableMapping(qd, op, ammd, value);
    } else if (isParentPK(ammd)) {
      addParentFilter(op, internalPkToKey(qd.acmd, value), qd);
    } else {
      String datastorePropName;
      if (ammd.isPrimaryKey()) {
        if (value instanceof Collection) {
          // let's handle this as a batch get request
          if (!qd.datastoreQuery.getFilterPredicates().isEmpty()) {
            // can only do a batch get if no other filters defined
            throwInvalidBatchLookupException();
          } else if (!op.equals(Query.FilterOperator.EQUAL)) {
            throw new NucleusUserException(
                "Batch lookup by primary key is only supported with the equality operator.").setFatal();
          }
          qd.batchGetKeys = Utils.newHashSet();
          for (Object obj : (Collection) value) {
            qd.batchGetKeys.add(internalPkToKey(qd.acmd, obj));
          }
          return;
        }
        datastorePropName = Entity.KEY_RESERVED_PROPERTY;
        value = internalPkToKey(qd.acmd, value);
      } else {
        datastorePropName = determinePropertyName(ammd);
      }
      if (value instanceof Collection) {
        throw new NucleusUserException(
            "Collection parameters are only supported when filtering on primary key.").setFatal();
      }
      if (qd.batchGetKeys != null) {
        // can only do a batch get if no other filters defined
        throwInvalidBatchLookupException();
      }
      value = pojoParamToDatastoreParam(value);
      qd.datastoreQuery.addFilter(datastorePropName, op, value);
    }
  }

  private void throwInvalidBatchLookupException() {
    throw new NucleusUserException(
        "Batch lookup by primary key is only supported if no other filters are defined.").setFatal();
  }

  /**
   * Fetches the tuples of the provided expression, stripping off the first
   * tuple if there are multiple tuples, the table name is aliased, and the
   * first tuple matches the alias.
   */
  private List<String> getTuples(PrimaryExpression expr, String alias) {
    List<String> tuples = expr.getTuples();
    if (alias != null && tuples.size() > 1 && alias.equals(tuples.get(0))) {
      tuples = tuples.subList(1, tuples.size());
    }
    return tuples;
  }

  // TODO(maxr): Use TypeConversionUtils
  private Object pojoParamToDatastoreParam(Object param) {
    if (param instanceof Enum) {
      param = ((Enum) param).name();
    } else if (param instanceof byte[]) {
      param = new ShortBlob((byte[]) param);
    } else if (param instanceof Byte[]) {
      param = new ShortBlob(PrimitiveArrays.toByteArray(Arrays.asList((Byte[]) param)));
    } else if (param instanceof BigDecimal) {
      param = ((BigDecimal) param).doubleValue();
    } else if (param instanceof Character) {
      param = param.toString();
    }
    return param;
  }

  private NucleusException noMetaDataException(String member, String fullClassName) {
    return new NucleusUserException(
        "No meta-data for member named " + member + " on class " + fullClassName
            + ".  Are you sure you provided the correct member name in your query?").setFatal();
  }

  private Object negateNumber(Number negateMe) {
    if (negateMe instanceof BigDecimal) {
      // datastore doesn't support filtering by BigDecimal to convert to
      // double.
      return ((BigDecimal) negateMe).negate().doubleValue();
    } else if (negateMe instanceof Float) {
      return -((Float) negateMe);
    } else if (negateMe instanceof Double) {
      return -((Double) negateMe);
    }
    return -negateMe.longValue();
  }

  private JavaTypeMapping getMappingForFieldWithName(List<String> tuples, QueryData qd) {
    ClassLoaderResolver clr = getObjectManager().getClassLoaderResolver();
    AbstractClassMetaData acmd = qd.acmd;
    JavaTypeMapping mapping = null;
    // We might be looking for the mapping for a.b.c
    for (String tuple : tuples) {
      DatastoreTable table = qd.tableMap.get(acmd.getFullClassName());
      if (table == null) {
        table = getStoreManager().getDatastoreClass(acmd.getFullClassName(), clr);
        qd.tableMap.put(acmd.getFullClassName(), table);
      }
      // deepest mapping we have so far
      mapping = table.getMemberMapping(tuple);
      // set the class meta data to the class of the type of the field of the
      // mapping so that we go one deeper if there are any more tuples
      acmd = getMetaDataManager().getMetaDataForClass(mapping.getMemberMetaData().getType(), clr);
    }
    return mapping;
  }

  private AbstractMemberMetaData getMemberMetaData(
      AbstractClassMetaData acmd, List<String> tuples) {
    AbstractMemberMetaData ammd = acmd.getMetaDataForMember(tuples.get(0));
    if (tuples.size() == 1) {
      return ammd;
    }
    EmbeddedMetaData emd = ammd.getEmbeddedMetaData();
    // more than one tuple, so it must be embedded data
    for (String tuple : tuples.subList(1, tuples.size())) {
      if (emd == null) {
        throw new NucleusUserException(
            query.getSingleStringQuery() + ": Can only filter by properties of a sub-object if "
            + "the sub-object is embedded.").setFatal();
      }
      ammd = findMemberMetaDataWithName(tuple, emd.getMemberMetaData());
    }
    return ammd;
  }

  private AbstractMemberMetaData findMemberMetaDataWithName(
      String name, AbstractMemberMetaData[] ammdList) {
    for (AbstractMemberMetaData embedded : ammdList) {
      if (embedded.getName().equals(name)) {
        return embedded;
      }
    }
    // Not ok, but caller knows what to do
    return null;
  }

  private void processPersistenceCapableMapping(
      QueryData qd, Query.FilterOperator op, AbstractMemberMetaData ammd, Object value) {
    ClassLoaderResolver clr = getObjectManager().getClassLoaderResolver();
    AbstractClassMetaData acmd = getMetaDataManager().getMetaDataForClass(ammd.getType(), clr);
    Object jdoPrimaryKey;
    if (value instanceof Key || value instanceof String) {
      // This is a bit odd, but just to be nice we let users
      // provide the id itself rather than the object containing the id.
      jdoPrimaryKey = value;
    } else if (value instanceof Long || value instanceof Integer) {
      String kind = EntityUtils.determineKind(acmd, getObjectManager());
      jdoPrimaryKey = KeyFactory.createKey(kind, ((Number) value).longValue());
    } else if (value == null) {
      jdoPrimaryKey = null;
    } else {
      ApiAdapter apiAdapter = getObjectManager().getApiAdapter();
      jdoPrimaryKey =
          apiAdapter.getTargetKeyForSingleFieldIdentity(apiAdapter.getIdForObject(value));
      if (jdoPrimaryKey == null) {
        // JDO couldn't find a primary key value on the object, but that
        // doesn't mean the object doesn't have a pk.  It could instead mean
        // that the object is transient and doesn't have an associated state
        // manager.  In this scenario we need to work harder to extract the pk.
        // We'll create a StateManager for a fresh PC object and then copy the
        // pk field of the parameter value into the fresh PC object.  We will
        // the extract the PK value from the fresh PC object.  The reason we
        // don't want to associate the state manager with the parameter value is
        // that this would be a very surprising (and meaningful) side effect.
        StateManager sm = apiAdapter.newStateManager(getObjectManager(), acmd);
        sm.initialiseForHollow(null, null, value.getClass());
        sm.copyFieldsFromObject((PersistenceCapable) value, acmd.getPKMemberPositions());
        jdoPrimaryKey = sm.provideField(acmd.getPKMemberPositions()[0]);
      }
      if (jdoPrimaryKey == null) {
        throw new NucleusUserException(
            query.getSingleStringQuery() + ": Parameter value " + value
            + " does not have an id.").setFatal();
      }
    }
    Key valueKey = null;
    if (jdoPrimaryKey != null) {
      valueKey = internalPkToKey(qd.acmd, jdoPrimaryKey);
      verifyRelatedKeyIsOfProperType(ammd, valueKey, acmd);
    }
    if (!qd.tableMap.get(ammd.getAbstractClassMetaData().getFullClassName()).isParentKeyProvider(ammd)) {
      // Looks like a join.  If it can be satisfied by just extracting the
      // parent key from the provided key, fulfill it.
      if (op != Query.FilterOperator.EQUAL) {
        throw new UnsupportedDatastoreFeatureException(
            "Only the equals operator is supported on conditions involving the owning side of a "
            + "one-to-one.", query.getSingleStringQuery());
      }
      if (valueKey == null) {
        // User is asking for parents where child is null.  Unfortunately we
        // don't have a way to fulfill this because one-to-one is actually
        // implemented as a one-to-many
        throw new NucleusUserException(
            query.getSingleStringQuery() + ": Cannot query for parents with null children.").setFatal();
      }

      if (valueKey.getParent() == null) {
        throw new NucleusUserException(
            query.getSingleStringQuery() + ": Key of parameter value does not have a parent.").setFatal();
      }

      // The field is the child side of an owned one to one.  We can just add
      // the parent key to the query as an equality filter on id.
      qd.datastoreQuery.addFilter(
          Entity.KEY_RESERVED_PROPERTY, Query.FilterOperator.EQUAL, valueKey.getParent());
    } else if (valueKey == null) {
      throw new NucleusUserException(
          query.getSingleStringQuery() + ": Cannot query for objects with null parents.").setFatal();      
    } else {
      addParentFilter(op, valueKey, qd);
    }
  }

  private void verifyRelatedKeyIsOfProperType(
      AbstractMemberMetaData ammd, Key key, AbstractClassMetaData acmd) {
    String keyKind = key.getKind();
    String fieldKind =
        getIdentifierFactory().newDatastoreContainerIdentifier(acmd).getIdentifierName();
    if (!keyKind.equals(fieldKind)) {
      throw new NucleusUserException(query.getSingleStringQuery() + ": Field "
                                 + ammd.getFullFieldName() + " maps to kind " + fieldKind + " but"
                                 + " parameter value contains Key of kind " + keyKind ).setFatal();
    }
  }

  private String determinePropertyName(AbstractMemberMetaData ammd) {
    if (ammd.getColumn() != null) {
      return ammd.getColumn();
    } else if (ammd.getColumnMetaData() != null && ammd.getColumnMetaData().length != 0) {
      return ammd.getColumnMetaData()[0].getName();
    } else {
      return getIdentifierFactory().newDatastoreFieldIdentifier(ammd.getName()).getIdentifierName();
    }
  }

  private Key internalPkToKey(AbstractClassMetaData acmd, Object internalPk) {
    Key key;
    if (internalPk instanceof String) {
      try {
        key = KeyFactory.stringToKey((String) internalPk);
      } catch (IllegalArgumentException iae) {
        String kind =
            getIdentifierFactory().newDatastoreContainerIdentifier(acmd).getIdentifierName();
        key = KeyFactory.createKey(kind, (String) internalPk);
      }
    } else if (internalPk instanceof Long) {
      String kind =
          getIdentifierFactory().newDatastoreContainerIdentifier(acmd).getIdentifierName();
      key = KeyFactory.createKey(kind, (Long) internalPk);
    } else {
      key = (Key) internalPk;
    }
    return key;
  }

  private void addParentFilter(Query.FilterOperator op, Key key, QueryData qd) {
    // We only support queries on parent if it is an equality filter.
    if (op != Query.FilterOperator.EQUAL) {
      throw new UnsupportedDatastoreFeatureException("Operator is of type " + op + " but the "
          + "datastore only supports parent queries using the equality operator.",
          query.getSingleStringQuery());
    }

    if (key == null) {
      throw new UnsupportedDatastoreFeatureException(
          "Received a null parent parameter.  The datastore does not support querying for null parents.",
          query.getSingleStringQuery());
    }
    qd.datastoreQuery.setAncestor(key);
  }

  private void checkForUnsupportedOperator(Expression.Operator operator) {
    if (UNSUPPORTED_OPERATORS.contains(operator)) {
      throw new UnsupportedDatastoreOperatorException(query.getSingleStringQuery(),
          operator);
    }
  }

  private boolean isParentPK(AbstractMemberMetaData ammd) {
    return ammd.hasExtension(DatastoreManager.PARENT_PK);
  }

  // Exposed for tests
  Query getLatestDatastoreQuery() {
    return latestDatastoreQuery;
  }

  private ObjectManager getObjectManager() {
    return query.getObjectManager();
  }

  private DatastoreManager getDatastoreManager() {
    return (DatastoreManager) getObjectManager().getStoreManager();
  }

  private MetaDataManager getMetaDataManager() {
    return getObjectManager().getMetaDataManager();
  }

  // Specialization just exists to support tests
  static class UnsupportedDatastoreOperatorException extends
      UnsupportedOperationException {
    private final String queryString;
    private final Expression.Operator operator;
    private final String msg;

    UnsupportedDatastoreOperatorException(String queryString,
        Expression.Operator operator) {
      this(queryString, operator, null);
    }

    UnsupportedDatastoreOperatorException(String queryString,
        Expression.Operator operator, String msg) {
      super(queryString);
      this.queryString = queryString;
      this.operator = operator;
      this.msg = msg;
    }

    @Override
    public String getMessage() {
      return "Problem with query <" + queryString
          + ">: App Engine datastore does not support operator " + operator;
    }

    public Expression.Operator getOperation() {
      return operator;
    }
  }

  // Specialization just exists to support tests
  static class UnsupportedDatastoreFeatureException extends
      UnsupportedOperationException {

    UnsupportedDatastoreFeatureException(String msg, String queryString) {
      super("Problem with query <" + queryString + ">: " + msg);
    }
  }

  public interface NowProvider {
    Date now();
  }

  public static NowProvider NOW_PROVIDER = new NowProvider() {
    public Date now() {
      return new Date();
    }
  };
}