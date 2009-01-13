// Copyright 2008 Google Inc. All Rights Reserved.
package org.datanucleus.store.appengine.query;

import com.google.apphosting.api.datastore.DatastoreService;
import com.google.apphosting.api.datastore.Entity;
import com.google.apphosting.api.datastore.FetchOptions;
import static com.google.apphosting.api.datastore.FetchOptions.Builder.withLimit;
import static com.google.apphosting.api.datastore.FetchOptions.Builder.withOffset;
import com.google.apphosting.api.datastore.Key;
import com.google.apphosting.api.datastore.KeyFactory;
import com.google.apphosting.api.datastore.Query;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableMapBuilder;
import com.google.common.collect.Sets;

import org.datanucleus.ClassLoaderResolver;
import org.datanucleus.FetchPlan;
import org.datanucleus.ManagedConnection;
import org.datanucleus.ObjectManager;
import org.datanucleus.StateManager;
import org.datanucleus.exceptions.NucleusException;
import org.datanucleus.metadata.AbstractClassMetaData;
import org.datanucleus.metadata.AbstractMemberMetaData;
import org.datanucleus.query.compiler.QueryCompilation;
import org.datanucleus.query.expression.DyadicExpression;
import org.datanucleus.query.expression.Expression;
import org.datanucleus.query.expression.JoinExpression;
import org.datanucleus.query.expression.Literal;
import org.datanucleus.query.expression.OrderExpression;
import org.datanucleus.query.expression.ParameterExpression;
import org.datanucleus.query.expression.PrimaryExpression;
import org.datanucleus.store.FieldValues;
import org.datanucleus.store.appengine.DatastoreFieldManager;
import org.datanucleus.store.appengine.DatastoreManager;
import org.datanucleus.store.mapped.IdentifierFactory;
import org.datanucleus.store.mapped.MappedStoreManager;
import org.datanucleus.store.query.AbstractJavaQuery;
import org.datanucleus.util.Localiser;
import org.datanucleus.util.NucleusLogger;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

  static final Set<Expression.Operator> UNSUPPORTED_OPERATORS =
      Sets.newHashSet((Expression.Operator) Expression.OP_ADD,
          (Expression.Operator) Expression.OP_BETWEEN,
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

  private static final Map<Expression.Operator, Query.FilterOperator> DATANUCLEUS_OP_TO_APPENGINE_OP =
      new ImmutableMapBuilder<Expression.Operator, Query.FilterOperator>()
          .put(Expression.OP_EQ, Query.FilterOperator.EQUAL)
          .put(Expression.OP_GT, Query.FilterOperator.GREATER_THAN)
          .put(Expression.OP_GTEQ, Query.FilterOperator.GREATER_THAN_OR_EQUAL)
          .put(Expression.OP_LT, Query.FilterOperator.LESS_THAN)
          .put(Expression.OP_LTEQ, Query.FilterOperator.LESS_THAN_OR_EQUAL)
          .getMap();

  /**
   * The query that is generated by Datanucleus.
   */
  private final AbstractJavaQuery query;

  /**
   * The datastore query that we most recently executed.
   * This should only be used for testing.
   */
  private transient Query mostRecentDatastoreQuery;

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
  public List<?> performExecute(Localiser localiser, QueryCompilation compilation,
      long fromInclNo, long toExclNo, Map<String, ?> parameters) {

    validate(compilation);

    if (toExclNo == 0 ||
        (rangeValueIsSet(toExclNo)
            && rangeValueIsSet(fromInclNo)
            && (toExclNo - fromInclNo) <= 0)) {
      // short-circuit - no point in executing the query
      return Collections.emptyList();
    }
    final ObjectManager om = query.getObjectManager();
    long startTime = System.currentTimeMillis();
    if (NucleusLogger.QUERY.isDebugEnabled()) {
      NucleusLogger.QUERY.debug(localiser.msg("021046", "DATASTORE", query.getSingleStringQuery(), null));
    }
    MappedStoreManager sm = (MappedStoreManager) om.getStoreManager();
    ManagedConnection mconn = sm.getConnection(om);
    try {
      DatastoreService ds = (DatastoreService) mconn.getConnection();
      final ClassLoaderResolver clr = om.getClassLoaderResolver();
      final AbstractClassMetaData acmd =
          om.getMetaDataManager().getMetaDataForClass(query.getCandidateClass(), clr);
      String kind =
          getIdentifierFactory().newDatastoreContainerIdentifier(acmd).getIdentifierName();
      mostRecentDatastoreQuery = new Query(kind);
      addFilters(compilation, mostRecentDatastoreQuery, parameters, acmd);
      addSorts(compilation, mostRecentDatastoreQuery, acmd);
      Iterable<Entity> entities;
      FetchOptions opts = buildFetchOptions(fromInclNo, toExclNo);
      if (opts != null) {
        entities = ds.prepare(mostRecentDatastoreQuery).asIterable(opts);
      } else {
        entities = ds.prepare(mostRecentDatastoreQuery).asIterable();
      }
      if (NucleusLogger.QUERY.isDebugEnabled()) {
        NucleusLogger.QUERY.debug(localiser.msg("021074", "DATASTORE",
            "" + (System.currentTimeMillis() - startTime)));
      }

      Function<Entity, Object> entityToPojoFunc = new Function<Entity, Object>() {
        public Object apply(Entity entity) {
          return entityToPojo(entity, acmd, clr, (DatastoreManager) om.getStoreManager());
        }
      };
      return new StreamingQueryResult(query, entities, entityToPojoFunc);
    } finally {
      mconn.release();
    }
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
    if (rangeValueIsSet(fromInclNo)) {
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

  private Object entityToPojo(final Entity entity, final AbstractClassMetaData acmd,
      final ClassLoaderResolver clr, final DatastoreManager storeMgr) {
    return entityToPojo(entity, acmd, clr, storeMgr, query.getObjectManager(), query.getIgnoreCache());
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
      boolean ignoreCache) {
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
        return null;
      }
    };
    return om.findObjectUsingAID(clr.classForName(acmd.getFullClassName()), fv, ignoreCache, true);
  }

  private void validate(QueryCompilation compilation) {
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
    // TODO(maxr): Add checks for subqueries and anything else we don't
    // allow.
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
  private void addSorts(QueryCompilation compilation, Query q, AbstractClassMetaData acmd) {
    Expression[] orderBys = compilation.getExprOrdering();
    if (orderBys == null) {
      return;
    }
    for (Expression expr : orderBys) {
      OrderExpression oe = (OrderExpression) expr;
      Query.SortDirection dir =
          oe.getSortOrder() == null || oe.getSortOrder().equals("ascending")
              ? Query.SortDirection.ASCENDING : Query.SortDirection.DESCENDING;
      String sortProp = ((PrimaryExpression) oe.getLeft()).getId();
      AbstractMemberMetaData ammd = acmd.getMetaDataForMember(sortProp);
      if (isAncestorPK(ammd)) {
        throw new UnsupportedDatastoreFeatureException(
            "Cannot sort by ancestor.", query.getSingleStringQuery());
      } else {
        if (ammd.isPrimaryKey()) {
          sortProp = Entity.KEY_RESERVED_PROPERTY;
        } else {
          sortProp = determinePropertyName(ammd);
        }
        q.addSort(sortProp, dir);
      }
    }
  }

  IdentifierFactory getIdentifierFactory() {
    return ((MappedStoreManager)query.getObjectManager().getStoreManager()).getIdentifierFactory();
  }

  /**
   * Adds filters to the given {@link Query} by examining the compiled filter
   * expression.
   */
  private void addFilters(QueryCompilation compilation, Query q, Map parameters,
      AbstractClassMetaData acmd) {
    Expression filter = compilation.getExprFilter();
    QueryData qd = new QueryData(q, parameters, acmd);
    addExpression(filter, qd);
  }

  /**
   * Struct used to represent info about the query we need to fulfill.
   */
  private static final class QueryData {
    private final Query query;
    private final Map parameters;
    private final AbstractClassMetaData acmd;

    private QueryData(Query query, Map parameters, AbstractClassMetaData acmd) {
      this.query = query;
      this.parameters = parameters;
      this.acmd = acmd;
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
        addLeftPrimaryExpression((PrimaryExpression) expr.getLeft(), expr
            .getOperator(), expr.getRight(), qd);
      } else {
        // Recurse!
        addExpression(expr.getLeft(), qd);
        addExpression(expr.getRight(), qd);
      }
    } else if (expr instanceof PrimaryExpression) {
      // Recurse!
      addExpression(expr.getLeft(), qd);
      addExpression(expr.getRight(), qd);
    } else {
      throw new UnsupportedDatastoreFeatureException(
          "Unexpected expression type while parsing query: "
              + expr.getClass().getName(), query.getSingleStringQuery());
    }
  }

  private void addLeftPrimaryExpression(PrimaryExpression left,
      Expression.Operator operator, Expression right, QueryData qd) {
    String propName = left.getId();
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
      value = right.getSymbol().getValue();
    } else {
      // We hit an operator that is not in the unsupported list and does
      // not have an entry in DATANUCLEUS_OP_TO_APPENGINE_OP. Almost certainly
      // a programming error.
      throw new UnsupportedDatastoreFeatureException(
          "Right side of expression is of unexpected type: " + right.getClass().getName(),
          query.getSingleStringQuery());
    }
    AbstractMemberMetaData ammd = qd.acmd.getMetaDataForMember(propName);
    if (ammd == null) {
      throw new NucleusException(
          "No meta-data for member named " + propName + " on class " + qd.acmd.getFullClassName()
              + ".  Are you sure you provided the correct member name?");
    }
    if (isAncestorPK(ammd)) {
      addAncestorFilter(op, qd, value);
    } else {
      if (ammd.isPrimaryKey()) {
        propName = Entity.KEY_RESERVED_PROPERTY;
        if (value instanceof String) {
          value = KeyFactory.decodeKey((String) value);
        }
      } else {
        propName = determinePropertyName(ammd);
      }
      qd.query.addFilter(propName, op, value);
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

  private void addAncestorFilter(Query.FilterOperator op, QueryData qd, Object value) {
    // We only support queries on ancestor if it is an equality filter.
    if (op != Query.FilterOperator.EQUAL) {
      throw new UnsupportedDatastoreFeatureException("Operator is of type " + op + " but the "
          + "datastore only supports ancestor queries using the equality operator.",
          query.getSingleStringQuery());
    }
    // value must be String or Key
    Key ancestor = (value instanceof String) ? KeyFactory.decodeKey((String) value) : (Key) value;
    qd.query.setAncestor(ancestor);
  }

  private void checkForUnsupportedOperator(Expression.Operator operator) {
    if (UNSUPPORTED_OPERATORS.contains(operator)) {
      throw new UnsupportedDatastoreOperatorException(query.getSingleStringQuery(),
          operator);
    }
  }

  private boolean isAncestorPK(AbstractMemberMetaData ammd) {
    return ammd.hasExtension("ancestor-pk");
  }

  // Exposed for tests
  Query getMostRecentDatastoreQuery() {
    return mostRecentDatastoreQuery;
  }

  // Specialization just exists to support tests
  static class UnsupportedDatastoreOperatorException extends
      UnsupportedOperationException {
    private final String queryString;
    private final Expression.Operator operator;

    UnsupportedDatastoreOperatorException(String queryString,
        Expression.Operator operator) {
      super(queryString);
      this.queryString = queryString;
      this.operator = operator;
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
}