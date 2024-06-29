/*
 * Copyright (c) 2023 - Manifold Systems LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package manifold.sql.rt.impl;

import manifold.rt.api.util.ManClassUtil;
import manifold.sql.rt.api.*;
import manifold.util.concurrent.ConcurrentHashSet;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 */
class BasicTxScope implements OperableTxScope
{
  private final DbConfig _dbConfig;
  private final Set<Entity> _rows;
  private final Set<Entity> _processedRows;
  private final ReentrantReadWriteLock _lock;
  private final List<BaseConsumer> _sqlChanges;
  private final List<BatchRunner> _batchRunners;
  private final Map<String, BatchRunner> _batchedChanges;
  private Connection _connection;

  public BasicTxScope( Class<? extends SchemaType> schemaClass )
  {
    _dbConfig = Dependencies.instance().getDbConfigProvider()
      .loadDbConfig( ManClassUtil.getShortClassName( schemaClass ), schemaClass );
    _rows = new LinkedHashSet<>();
    _processedRows = new ConcurrentHashSet<>();
    _sqlChanges = new ArrayList<>();
    _batchRunners = new ArrayList<>();
    _batchedChanges = new LinkedHashMap<>();
    _lock = new ReentrantReadWriteLock();
  }

  @Override
  public DbConfig getDbConfig()
  {
    return _dbConfig;
  }

  @Override
  public Set<Entity> getRows()
  {
    _lock.readLock().lock();
    try
    {
      return new HashSet<>( _rows );
    }
    finally
    {
      _lock.readLock().unlock();
    }
  }

  @Override
  public void addRow( Entity item )
  {
    if( item == null )
    {
      throw new IllegalArgumentException( "Item is null" );
    }

    if( _processedRows.remove( item ) )
    {
      ((OperableTxBindings)item.getBindings()).reuse();
    }

    if( containsRow( item  ) )
    {
      return;
    }

    _lock.writeLock().lock();
    try
    {
      _rows.add( item );
    }
    finally
    {
      _lock.writeLock().unlock();
    }
  }

  @Override
  public void removeRow( Entity item )
  {
    _lock.writeLock().lock();
    try
    {
      _rows.remove( item );
    }
    finally
    {
      _lock.writeLock().unlock();
    }
  }

  @Override
  public boolean containsRow( Entity item )
  {
    _lock.readLock().lock();
    try
    {
      return _rows.contains( item );
    }
    finally
    {
      _lock.readLock().unlock();
    }
  }

  @Override
  public void addSqlChange( ScopeConsumer change )
  {
    if( change == null )
    {
      throw new IllegalArgumentException( "'change' is null" );
    }

    _lock.writeLock().lock();
    try
    {
      _sqlChanges.add( change );
    }
    finally
    {
      _lock.writeLock().unlock();
    }
  }

  @Override
  public void addBatchChange( BatchScopeConsumer change )
  {
    if( change == null )
    {
      throw new IllegalArgumentException( "'change' is null" );
    }

    _lock.writeLock().lock();
    try
    {
      _sqlChanges.add( change );
    }
    finally
    {
      _lock.writeLock().unlock();
    }
  }

  private void addBatchRunner( BatchRunner change )
  {
    _lock.writeLock().lock();
    try
    {
      _batchRunners.add( change );
    }
    finally
    {
      _lock.writeLock().unlock();
    }
  }

  @Override
  public void addBatch( Executor exec, Consumer<Statement> stuff )
  {
    _lock.writeLock().lock();
    try
    {
      String batchId = ((BatchSqlChangeCtx)exec.getCtx()).getBatchId();
      String sql = exec.getSqlCommand();
      _batchedChanges.computeIfAbsent( batchId, __ -> new BatchRunner( batchId, sql ) )
        .addBatchedItem( stuff );
    }
    finally
    {
      _lock.writeLock().unlock();
    }
  }

  private class BatchRunner implements ScopeConsumer
  {
    private final String _batchId;
    private final String _sql;
    private final List<Consumer<Statement>> _batchItems;

    private BatchRunner( String batchId, String sql )
    {
      // either "_stmt_" for adding many non-parameterized sql statements into one batch, or the class name of a
      // parameterized sql command statement e.g., inserting many rows into a single table where only params differ
      _batchId = batchId;

      // sql is necessary only for parameterized PreparedStatements where the sql is static and the parameters vary:
      // PreparedStatement.setParameters() vs. Statement.addBatch(sql)
      _sql = batchId.equals( "_stmt_" ) ? null : sql;

      _batchItems = new ArrayList<>();
      addBatchRunner( this );
    }

    private void addBatchedItem( Consumer<Statement> batchItem )
    {
      _batchItems.add( batchItem );
    }

    @Override
    public void accept( SqlChangeCtx ctx ) throws SQLException
    {
      if( _batchId.equals( "_stmt_" ) )
      {
        // sql commands with no parameters are batched in a single statement
        try( Statement stmt = ctx.getConnection().createStatement() )
        {
          for( Consumer<Statement> batchItem : _batchItems )
          {
            batchItem.accept( stmt );
          }
          stmt.executeBatch();
        }
      }
      else
      {
        // sql commands with parameters are batched one prepared stmt to its many parameter settings
        try( PreparedStatement ps = ctx.getConnection().prepareStatement( _sql ) )
        {
          for( Consumer<Statement> batchItem : _batchItems )
          {
            batchItem.accept( ps );
            ps.addBatch();
          }
          ps.executeBatch();
        }
      }
    }
  }

  @Override
  public void commit() throws SQLException
  {
    _lock.writeLock().lock();
    try
    {
      if( _rows.isEmpty() && _sqlChanges.isEmpty() )
      {
        // no changes to commit
        return;
      }

      Object commitItem = _rows.isEmpty() ? _sqlChanges.get( 0 ) : _rows.stream().findFirst().get();

      ConnectionProvider cp = Dependencies.instance().getConnectionProvider();
      try( Connection c = cp.getConnection( getDbConfig().getName(), commitItem.getClass() ) )
      {
        _connection = c;
        try
        {
          c.setAutoCommit( false );

          doCrud( c );

          for( BaseConsumer sqlChange : _sqlChanges )
          {
            if( sqlChange instanceof BatchScopeConsumer )
            {
              // The execution of BatchScopeConsumers results in the creation of BatchRunners.
              // A BatchScopeConsumer produces a list of "addBatch" calls, that can be either:
              // - add a set of parameters for a PreparedStatement having a single sql statement, which batches the varying parameterizations on the single sql statement, or
              // - add a sql command to a Statement, which batches the sql statements as one call
              // Each set of "addBatch" calls is mapped by an ID within a BatchRunner.
              // A BatchRunner is itself a ScopeConsumer and executes the addBatch calls immediately after the
              // BatchScopeConsumer executes as part of executeBatchSqlChange().
              executeBatchSqlChange( c, (BatchScopeConsumer)sqlChange );
            }
            else
            {
              executeSqlChange( c, (ScopeConsumer)sqlChange );
            }
          }

          doCrud( c );

          c.commit();

          for( Entity row : _rows )
          {
            ((OperableTxBindings)row.getBindings()).commit();
          }

          _rows.clear();
          _sqlChanges.clear();
          _batchRunners.clear();
          _batchedChanges.clear();
        }
        catch( SQLException e )
        {
          c.rollback();

          for( Entity row : _rows )
          {
            ((OperableTxBindings)row.getBindings()).failedCommit();
          }

          throw e;
        }
        finally
        {
          _connection = null;
        }
      }
    }
    finally
    {
      _lock.writeLock().unlock();
    }
  }

  private void doCrud( Connection c ) throws SQLException
  {
    Set<Entity> visited = new HashSet<>();
    for( Entity row : _rows )
    {
      doCrud( c, row, new LinkedHashMap<>(), visited );
    }
  }

  private void executeBatchRunners( Connection c ) throws SQLException
  {
    while( !_batchRunners.isEmpty() )
    {
      BatchRunner batchChange = _batchRunners.remove( 0 );
      _batchedChanges.remove( batchChange._batchId );

      executeSqlChange( c, batchChange );
    }
  }

  @Override
  public void commit( ScopeConsumer change ) throws SQLException
  {
    addSqlChange( change );
    commit();
  }

  @Override
  public void commitAsBatch( BatchScopeConsumer change ) throws SQLException
  {
    addBatchChange( change );
    commit();
  }

  private void executeSqlChange( Connection c, ScopeConsumer sqlChange ) throws SQLException
  {
    sqlChange.accept( newSqlChangeCtx( c ) );
  }

  private void executeBatchSqlChange( Connection c, BatchScopeConsumer sqlChange ) throws SQLException
  {
    sqlChange.accept( newBatchSqlChangeCtx( c ) );
    executeBatchRunners( c );
  }

  @Override
  public SqlChangeCtx newSqlChangeCtx( Connection c )
  {
    return new MySqlChangeCtx( c );
  }

  @Override
  public BatchSqlChangeCtx newBatchSqlChangeCtx( Connection c )
  {
    return new MyBatchSqlChangeCtx( c );
  }

  @Override
  public void revert() throws SQLException
  {
    if( _connection != null )
    {
      throw new SQLException( "Revert is not supported within a transaction" );
    }

    _lock.writeLock().lock();
    try
    {
      for( Entity row : _rows )
      {
        ((OperableTxBindings)row.getBindings()).revert();
      }                   
      _rows.clear();
      _sqlChanges.clear();
      _batchRunners.clear();
      _batchedChanges.clear();
    }
    finally
    {
      _lock.writeLock().unlock();
    }
  }

  private void doCrud( Connection c, Entity row, Map<Entity, Set<FkDep>> unresolvedDeps, Set<Entity> visited ) throws SQLException
  {
    if( visited.contains( row ) )
    {
      return;
    }
    visited.add( row );

    if( _processedRows.contains( row ) )
    {
      return;
    }
    _processedRows.add( row );

    doFkDependenciesFirst( c, row, unresolvedDeps, visited );

    CrudProvider crud = Dependencies.instance().getCrudProvider();

    TableInfo ti = row.tableInfo();
    UpdateContext<Entity> ctx = new UpdateContext<>( this, row, ti.getDdlTableName(), _dbConfig.getName(),
      ti.getPkCols(), ti.getUkCols(), ti.getAllCols() );

    if( row.getBindings().isForInsert() )
    {
      crud.create( c, ctx );
    }
    else if( row.getBindings().isForUpdate() )
    {
      crud.update( c, ctx );
    }
    else if( row.getBindings().isForDelete() )
    {
      crud.delete( c, ctx );
    }
    else
    {
      throw new SQLException( "Unexpected bindings kind, neither of insert/update/delete" );
    }
    patchUnresolvedFkDeps( c, ctx, crud, unresolvedDeps.get( row ) );
  }

  /**
   * Note, unresolved fk dependencies happen when there are fk cycles e.g., Foo has fk on Bar's pk, Bar has fk on Foo's pk
   * If the database platform supports Deferrable constraints, the fk constraints are not enforced until commit ("Initially Deferred"),
   * which enables the handling of cycles by allowing a null value for an fk before commit.
   */
  private void patchUnresolvedFkDeps( Connection c, UpdateContext<Entity> ctx, CrudProvider crud, Set<FkDep> unresolvedDeps ) throws SQLException
  {
    if( unresolvedDeps == null )
    {
      return;
    }

    for( FkDep dep : unresolvedDeps )
    {
      Object pkId = ((OperableTxBindings)dep.pkRow.getBindings()).getHeldValue( dep.pkName );
      if( pkId == null )
      {
        throw new SQLException( "pk value is null" );
      }

      // update the fk column that was null initially due to fk cycle
      OperableTxBindings fkBindings = (OperableTxBindings)dep.fkRow.getBindings();
      Object priorPkId = fkBindings.put( dep.fkName, pkId );
      try
      {
        // re-update with same values + fkId
        crud.update( c, ctx );
      }
      finally
      {
        // put old value back into changes (s/b Pair<Entity, String>)
        fkBindings.put( dep.fkName, priorPkId );
        // put id into hold values because it should not take effect until commit
        fkBindings.holdValue( dep.fkName, pkId );
      }
    }
  }

  private void doFkDependenciesFirst( Connection c, Entity row, Map<Entity, Set<FkDep>> unresolvedDeps, Set<Entity> visited ) throws SQLException
  {
    for( Map.Entry<String, Object> entry : row.getBindings().entrySet() )
    {
      Object value = entry.getValue();
      if( value instanceof KeyRef )
      {
        KeyRef ref = (KeyRef)value;
        Entity pkEntity = ref.getRef();
        FkDep fkDep = new FkDep( row, entry.getKey(), pkEntity, ref.getKeyColName() );

        doCrud( c, pkEntity, unresolvedDeps, visited );

        // patch fk
        Object pkId = ((OperableTxBindings)pkEntity.getBindings()).getHeldValue( fkDep.pkName );
        if( pkId != null )
        {
          // pkEntity was inserted, assign fk value now, this is assigned as an INSERT param in BasicCrudProvider when
          // the value of the param is a KeyRef
          ((OperableTxBindings)row.getBindings()).holdValue( fkDep.fkName, pkId );
        }
        else
        {
          // pkEntity not inserted yet, presumably due to a fk cycle, assign a temp fk value as INSERT param and resolve
          // the fk assignment later
          unresolvedDeps.computeIfAbsent( pkEntity, __ -> new LinkedHashSet<>() )
            .add( fkDep );
        }
      }
    }
  }

  @Override
  public Connection getActiveConnection()
  {
    return _connection;
  }

  private static class FkDep
  {
    final Entity fkRow;
    final String fkName;
    final Entity pkRow;
    final String pkName;

    public FkDep( Entity fkRow, String fkName, Entity pkRow, String pkName )
    {
      this.fkRow = fkRow;
      this.fkName = fkName;
      this.pkRow = pkRow;
      this.pkName = pkName;
    }
  }

  private class MySqlChangeCtx implements SqlChangeCtx
  {
    private final Connection _c;

    public MySqlChangeCtx( Connection c )
    {
      _c = c;
    }

    @Override
    public TxScope getTxScope()
    {
      return BasicTxScope.this;
    }

    @Override
    public Connection getConnection()
    {
      return _c;
    }

    @Override
    public void doCrud() throws SQLException
    {
      BasicTxScope.this.doCrud( _c );
    }
  }

  private class MyBatchSqlChangeCtx extends MySqlChangeCtx implements BatchSqlChangeCtx
  {
    private String _batchId;

    public MyBatchSqlChangeCtx( Connection c )
    {
      super( c );
    }

    @Override
    public String getBatchId()
    {
      return _batchId;
    }
    @Override
    public void setBatchId( String batchId )
    {
      _batchId = batchId;
    }
  }
}
