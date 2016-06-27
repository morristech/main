/*******************************************************************************
 * Copyright (c) 2009, 2016 Erik Berglund.
 *    
 *        This file is part of Conserve.
 *    
 *        Conserve is free software: you can redistribute it and/or modify
 *        it under the terms of the GNU Affero General Public License as published by
 *        the Free Software Foundation, either version 3 of the License, or
 *        (at your option) any later version.
 *    
 *        Conserve is distributed in the hope that it will be useful,
 *        but WITHOUT ANY WARRANTY; without even the implied warranty of
 *        MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *        GNU Affero General Public License for more details.
 *    
 *        You should have received a copy of the GNU Affero General Public License
 *        along with Conserve.  If not, see <https://www.gnu.org/licenses/agpl.html>.
 *******************************************************************************/
package org.conserve;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.conserve.adapter.AdapterBase;
import org.conserve.adapter.DerbyAdapter;
import org.conserve.adapter.FirebirdAdapter;
import org.conserve.adapter.HsqldbAdapter;
import org.conserve.adapter.MariaAdapter;
import org.conserve.adapter.MonetDbAdapter;
import org.conserve.adapter.MySqlAdapter;
import org.conserve.adapter.PostgreSqlAdapter;
import org.conserve.adapter.SqLiteAdapter;
import org.conserve.aggregate.AggregateFunction;
import org.conserve.aggregate.Count;
import org.conserve.aggregate.Sum;
import org.conserve.cache.ObjectRowMap;
import org.conserve.connection.ConnectionWrapper;
import org.conserve.connection.DataConnectionPool;
import org.conserve.exceptions.SchemaPermissionException;
import org.conserve.select.Clause;
import org.conserve.sort.DatabaseIDSorter;
import org.conserve.sort.Order;
import org.conserve.tools.ArrayEntryWriter;
import org.conserve.tools.ArrayLoader;
import org.conserve.tools.ClassIdTuple;
import org.conserve.tools.Defaults;
import org.conserve.tools.DelayedInsertionBuffer;
import org.conserve.tools.Duplicator;
import org.conserve.tools.NameGenerator;
import org.conserve.tools.ObjectFactory;
import org.conserve.tools.ObjectTools;
import org.conserve.tools.StatementPrototype;
import org.conserve.tools.TableManager;
import org.conserve.tools.Tools;
import org.conserve.tools.Updater;
import org.conserve.tools.generators.StatementPrototypeGenerator;
import org.conserve.tools.metadata.ConcreteObjectRepresentation;
import org.conserve.tools.metadata.MapEntry;
import org.conserve.tools.metadata.ObjectRepresentation;
import org.conserve.tools.metadata.ObjectStack;
import org.conserve.tools.protection.ProtectionManager;

/**
 * Object database interface backend. Handles the requests issued by
 * PersistenceManager.
 * 
 * 
 * @author Erik Berglund
 * 
 */
public class Persist
{
	private DataConnectionPool connectionPool;

	private boolean createSchema;

	/**
	 * Default adapter object.
	 */
	private AdapterBase adapter;

	/**
	 * Keeps track of what objects are already in the database.
	 */
	private ObjectRowMap cache = new ObjectRowMap();
	private TableManager tableManager;
	private ProtectionManager protectionManager;
	private Updater updater;
	private ArrayEntryWriter arrayEntryWriter;
	
	private static final Logger LOGGER = Logger.getLogger("org.conserve");

	/**
	 * Package-access constructor.
	 */
	Persist()
	{
		cache.start();
	}

	void initialize(Properties prop) throws SQLException
	{
		String driver = prop.getProperty("org.conserve.driver");
		String connectionString = prop.getProperty("org.conserve.connectionstring");
		if (connectionString == null)
		{
			throw new SQLException("Property org.conserve.connectionstring not found.");
		}
		String userName = prop.getProperty("org.conserve.username");
		if (userName == null)
		{
			throw new SQLException("Property org.conserve.username not found.");
		}
		String password = prop.getProperty("org.conserve.password");
		if (password == null)
		{
			throw new SQLException("Property org.conserve.password not found.");
		}
		initialize(driver, connectionString, userName, password);
	}

	void initialize(String driver, String connectionstring, String username, String password) throws SQLException
	{
		// create the pool
		connectionPool = new DataConnectionPool(1, driver, connectionstring, username, password);
		// set up the default adapter
		adapter = selectAdapter(connectionstring);
		LOGGER.fine("Selected adapter: " + NameGenerator.getSystemicName(adapter.getClass()) + " for connection " + connectionstring);
		// set up a new protection manager
		protectionManager = new ProtectionManager();
		// set up the object responsible for updating objects
		updater = new Updater(adapter);

		arrayEntryWriter = new ArrayEntryWriter(adapter);

		// set up a manager for system tables and table creation
		tableManager = new TableManager(this.isCreateSchema(), connectionPool, adapter);
		try
		{
			tableManager.initializeSystemTables();
		}
		catch (SchemaPermissionException e)
		{
			connectionPool.cleanUp();
			connectionPool = null;
			throw new SQLException(e);
		}
	}

	/**
	 * Select the appropriate AdapterBase implementation for a given database
	 * url.
	 * 
	 * @param url
	 * @return
	 */
	private AdapterBase selectAdapter(String url)
	{
		AdapterBase res = null;
		if (url.startsWith("jdbc:mysql:"))
		{
			res = new MySqlAdapter(this);
		}
		else if (url.startsWith("jdbc:mariadb:"))
		{
			res = new MariaAdapter(this);
		}
		else if (url.startsWith("jdbc:postgresql:"))
		{
			res = new PostgreSqlAdapter(this);
		}
		else if (url.startsWith("jdbc:derby:"))
		{
			res = new DerbyAdapter(this);
		}
		else if (url.startsWith("jdbc:hsqldb:"))
		{
			res = new HsqldbAdapter(this);
		}
		else if (url.startsWith("jdbc:firebirdsql:"))
		{
			res = new FirebirdAdapter(this);
		}
		else if (url.startsWith("jdbc:sqlite:"))
		{
			res = new SqLiteAdapter(this);
		}
		else if (url.startsWith("jdbc:monetdb"))
		{
			res = new MonetDbAdapter(this);
		}
		// TODO: If you want to extend Conserve to handle a new RDBMS,
		// add the case here and return a custom subclass of AdapterBase.
		else
		{
			// use the default (H2) adapter.
			res = new AdapterBase(this);
		}
		return res;
	}

	/**
	 * Indicate whether tables and indices should be automatically created.
	 * 
	 * @param createSchema
	 */
	public void setCreateSchema(boolean createSchema)
	{
		this.createSchema = createSchema;
		if (this.tableManager != null)
		{
			tableManager.setCreateSchema(createSchema);
		}
	}

	/**
	 * Check if this object automatically creates schema as needed.
	 * 
	 * @return true if tables are automatically created.
	 */
	public boolean isCreateSchema()
	{
		return this.createSchema;
	}

	/**
	 * Get a wrapped SQL connection to the database used by this Persist
	 * instance. To use the connection, call the getConnection() method on the
	 * returned object.
	 * 
	 * @return a ready-to-use ConnectionWrapper object.
	 * @throws SQLException
	 */
	public ConnectionWrapper getConnectionWrapper() throws SQLException
	{
		return this.connectionPool.getConnectionWrapper();
	}

	/**
	 * Delete all objects of class clazz (or any of its subclasses) that satisfy
	 * the where clause. If clazz is an interface, delete all implementing
	 * classes that satisfy the where clause.
	 * 
	 * @param clazz
	 *            the class of objects to delete.
	 * @param where
	 *            the clause that must be satisfied for an object to be deleted.
	 * @param cw
	 *            the connection wrapper to use for this operation.
	 * @return the number of deleted objects.
	 */
	public <T> int deleteObjects(ConnectionWrapper cw, Class<T> clazz, Clause where) throws SQLException
	{
		int res = 0;
		try
		{
			int deletedCount = 0;
			String className = NameGenerator.getSystemicName(clazz);
			// find all matching objects
			HashMap<Class<?>, List<Long>> objectDescr = getObjectDescriptors(cw,clazz, className, where, null);
			for (Entry<Class<?>, List<Long>> en : objectDescr.entrySet())
			{
				Class<?> c = en.getKey();
				String tableName = NameGenerator.getTableName(c, adapter);
				List<Long> ids = en.getValue();
				for (Long id : ids)
				{
					// delete objects from cache
					cache.purge(tableName, id);
					// unprotect the objects
					protectionManager.unprotectObjectExternal(tableName, id, cw);
					// check if objects are part of other object
					if (!protectionManager.isProtected(tableName, id, cw))
					{
						// if not, delete
						deleteObject(cw,c, id);
						deletedCount++;

					}
				}
			}
			res = deletedCount;
		}
		catch (ClassNotFoundException e)
		{
			throw new SQLException(e);
		}
		return res;
	}


	/**
	 * Delete an object, recursively clearing corresponding entries in
	 * superclass tables.
	 * 
	 * @param clazz
	 *            the type of object to delete.
	 * @param id
	 *            the database id to delete.
	 * @param cw
	 *            the ConnectionWrapper to use for this transaction.
	 * 
	 * @return true if one object was deleted, false otherwise.
	 * 
	 * @throws SQLException
	 */
	public boolean deleteObject(ConnectionWrapper cw,Class<?> clazz, Long id ) throws SQLException
	{
		boolean res = false;
		String tableName = NameGenerator.getTableName(clazz, adapter);
		// delete object itself and its properties
		res = deleteObject(cw,tableName, id);

		// get a list of all classes to delete from
		List<Class<?>> toDelete = ObjectTools.getAllLegalReferenceTypes(clazz);
		for (Class<?> c : toDelete)
		{
			res &= deleteObjectHelper(cw,c, id);
		}
		return res;
	}

	/**
	 * Delete one particular object.
	 * 
	 * @param tableName
	 * @param id
	 * @param cw
	 * @return true if one object was deleted, false otherwise.
	 * @throws SQLException
	 */
	public boolean deleteObject(ConnectionWrapper cw,String tableName, Long id) throws SQLException
	{
		boolean res = false;
		if (tableName.equalsIgnoreCase(Defaults.ARRAY_TABLENAME))
		{
			// this means the object is an array, delete the array
			try
			{
				res = deleteArray(cw,id);
			}
			catch (ClassNotFoundException e)
			{
				throw new SQLException(e);
			}
		}
		else
		{
			StringBuilder statement = new StringBuilder("DELETE FROM ");
			statement.append(tableName);
			statement.append(" WHERE ");
			statement.append(Defaults.ID_COL);
			statement.append(" = ?");
			PreparedStatement ps = cw.prepareStatement(statement.toString());
			ps.setLong(1, id);
			Tools.logFine(ps);
			res = ps.executeUpdate() == 1;
			ps.close();
			// properties of non-arrays are deleted after the object itself
			deletePropertiesOf(cw,id);
		}
		return res;
	}

	/**
	 * Delete the array with the given id.
	 * 
	 * @param id
	 * @param cw
	 * @throws SQLException
	 * @throws ClassNotFoundException
	 */
	private boolean deleteArray(ConnectionWrapper cw,Long id) throws SQLException, ClassNotFoundException
	{
		boolean res = false;
		// delete all the array's members before deleting the array itself
		// find the array contents class
		StringBuilder arrayQuery = new StringBuilder("SELECT COMPONENT_TABLE,COMPONENT_TYPE FROM ");
		arrayQuery.append(NameGenerator.getArrayTablename(adapter));
		arrayQuery.append(" WHERE ");
		arrayQuery.append(Defaults.ID_COL);
		arrayQuery.append(" = ? ");
		PreparedStatement aQuery = cw.prepareStatement(arrayQuery.toString());
		aQuery.setLong(1, id);
		Tools.logFine(aQuery);
		ResultSet rs = aQuery.executeQuery();
		if (rs.next())
		{
			String compType = rs.getString(2);
			Class<?> compClass = ObjectTools.lookUpClass(compType, adapter).getComponentType();
			String compTable = NameGenerator.getArrayMemberTableName(compClass, adapter);
			boolean isObject = !ObjectTools.isDatabasePrimitive(compClass);

			// all entries are actual objects, delete them
			StringBuilder componentQuery = new StringBuilder(
					"SELECT " + Defaults.COMPONENT_CLASS_COL + ", " + Defaults.VALUE_COL + ", " + Defaults.ID_COL + " FROM ");
			componentQuery.append(compTable);
			componentQuery.append(" WHERE C__ARRAY_MEMBER_ID = ?");
			PreparedStatement componentStmt = cw.prepareStatement(componentQuery.toString());
			componentStmt.setLong(1, id);
			Tools.logFine(componentStmt);
			ResultSet components = componentStmt.executeQuery();
			while (components.next())
			{
				String type = components.getString(1);
				Long valueId = components.getLong(2);
				Long compId = components.getLong(3);
				Class<?> clazz = ObjectTools.lookUpClass(type, adapter);
				String table = NameGenerator.getTableName(clazz, adapter);
				protectionManager.unprotectObjectInternal(NameGenerator.getArrayTablename(adapter), id, compTable, compId, cw);
				if (isObject)
				{
					protectionManager.unprotectObjectInternal(compTable, compId, table, valueId, cw);
					if (!protectionManager.isProtected(table, valueId, cw))
					{
						deleteObject(cw,clazz, valueId);
					}
				}
			}
			componentStmt.close();

			// delete all entries in the comp table
			String deleteCommand = "DELETE FROM " + compTable + " WHERE C__ARRAY_MEMBER_ID = ?";
			PreparedStatement deleteStmt = cw.prepareStatement(deleteCommand);
			deleteStmt.setLong(1, id);
			Tools.logFine(deleteStmt);
			deleteStmt.execute();
			deleteStmt.close();

		}
		aQuery.close();
		// delete the array entry
		StringBuilder statement = new StringBuilder("DELETE FROM ");
		statement.append(Defaults.ARRAY_TABLENAME);
		statement.append(" WHERE ");
		statement.append(Defaults.ID_COL);
		statement.append(" = ?");
		PreparedStatement ps = cw.prepareStatement(statement.toString());
		ps.setLong(1, id);
		Tools.logFine(ps);
		res = ps.executeUpdate() == 1;
		ps.close();
		// all arrays also have an entry in the Object, Serializable, and
		// Cloneable tables, so
		// we must delete those as well.
		deleteObjectHelper(cw,Object.class, id);
		deleteObjectHelper(cw,Serializable.class, id);
		deleteObjectHelper(cw,Cloneable.class, id);
		return res;
	}

	/**
	 * 
	 * @param clazz
	 *            the class to delete objects from.
	 * @param id
	 *            the ID of the class
	 * @param cw
	 *            the connection wrapper
	 * @return
	 * @throws SQLException
	 */
	private boolean deleteObjectHelper(ConnectionWrapper cw,Class<?> clazz, Long id) throws SQLException
	{
		boolean res = false;
		if (clazz != null)
		{
			String tableName = NameGenerator.getTableName(clazz, adapter);
			// delete the instance
			StringBuilder statement = new StringBuilder("DELETE FROM ");
			statement.append(tableName);
			statement.append(" WHERE ");
			statement.append(Defaults.ID_COL);
			statement.append(" = ? ");
			PreparedStatement ps = cw.prepareStatement(statement.toString());
			ps.setLong(1, id);
			Tools.logFine(ps);
			res = ps.executeUpdate() == 1;
			ps.close();

		}
		return res;
	}

	/**
	 * Recursively delete the properties of the object with id. Does not apply to arrays
	 * 
	 * @param id
	 *            the id of the object who's properties we are deleting.
	 * @throws SQLException
	 */
	private void deletePropertiesOf(ConnectionWrapper cw,Long id) throws SQLException
	{
		// find all properties
		StringBuilder statement = new StringBuilder("SELECT OWNER_TABLE,PROPERTY_TABLE, PROPERTY_ID, PROPERTY_CLASS FROM ");
		statement.append(Defaults.HAS_A_TABLENAME);
		statement.append(" WHERE OWNER_ID = ?");
		PreparedStatement query = cw.prepareStatement(statement.toString());
		query.setLong(1, id);
		Tools.logFine(query);
		ResultSet subObjects = query.executeQuery();
		while (subObjects.next())
		{
			String ownerTable = subObjects.getString(1);
			if (ownerTable.equalsIgnoreCase(Defaults.ARRAY_TABLENAME)
							|| ownerTable.toUpperCase().startsWith(Defaults.ARRAY_MEMBER_TABLENAME) )
			{
				continue;
			}
			String propertyTable = subObjects.getString(2);
			Long propertyId = subObjects.getLong(3);
			String propertyClassName = subObjects.getString(4);
			// unprotect the sub-object relative to the current object
			protectionManager.unprotectObjectInternal(ownerTable,id, propertyTable, propertyId, cw);
			// check if the property is unprotected
			if (!protectionManager.isProtected(propertyTable, propertyId, cw))
			{
				// delete the property itself
				try
				{
					//check if the peroperty is an array
					if (propertyTable.equalsIgnoreCase(Defaults.ARRAY_TABLENAME)
							|| propertyTable.toUpperCase().startsWith(Defaults.ARRAY_MEMBER_TABLENAME) || propertyClassName.contains("["))
					{
						deleteObject(cw,propertyTable, propertyId);
					}
					else
					{
						Class<?> c = ObjectTools.lookUpClass(propertyClassName, adapter);
						deleteObject(cw,c, propertyId);
					}
				}
				catch (ClassNotFoundException e)
				{
					query.close();
					throw new SQLException(e);
				}
			}
		}
		query.close();
	}


	/**
	 * Get a list of the classes and ids that satisfy the clause.
	 * 
	 * Either clazz or className must be non-null. If both are non-null they
	 * must refer to the same class, so that clazz.getCanonincaName() ==
	 * className.
	 * 
	 * @param clazz
	 *            the class to search for (can be null if className is given).
	 * @param className
	 *            the name of the class to search for (can be null if clazz is
	 *            given).
	 * @param clause
	 *            the clause to distinguish (can be null if id is given).
	 * @return id the database id of the object to search for (can be null if
	 *         clause is given).
	 * @throws ClassNotFoundException
	 * @throws SQLException
	 */
	@SuppressWarnings("unchecked")
	<T> HashMap<Class<?>, List<Long>> getObjectDescriptors(ConnectionWrapper cw, Class<T> clazz, String className, Clause clause, Long id)
			throws ClassNotFoundException, SQLException
	{
		HashMap<Class<?>, List<Long>> res = new HashMap<Class<?>, List<Long>>();
		if (className != null && className.equalsIgnoreCase(Defaults.ARRAY_TABLENAME))
		{
			// can't load array classes, so ignore
			return res;
		}
		if (clazz == null)
		{
			clazz = (Class<T>) ClassLoader.getSystemClassLoader().loadClass(className);
		}
		// check if the appropriate table exists
		String tableName = NameGenerator.getTableName(clazz, adapter);
		if (!tableManager.tableExists(tableName, cw))
		{
			return res;
		}

		StatementPrototypeGenerator whereGenerator = new StatementPrototypeGenerator(adapter);
		whereGenerator.setClauses(clause);
		StatementPrototype sp = whereGenerator.generate(clazz, clause != null);
		// get the id of clazz
		String shortName = whereGenerator.getTypeStack().getActualRepresentation().getAsName();
		if (id != null)
		{
			sp.addEqualsClause(shortName + "." + Defaults.ID_COL, id);
		}

		StringBuilder statement = new StringBuilder("SELECT DISTINCT(");
		statement.append(shortName);
		statement.append(".");
		statement.append(Defaults.ID_COL);
		statement.append("),");
		statement.append(shortName);
		statement.append(".");
		statement.append(Defaults.REAL_CLASS_COL);
		statement.append(" FROM ");
		PreparedStatement ps = sp.toPreparedStatement(cw, statement.toString());
		ResultSet rs = ps.executeQuery();
		List<HashMap<String, Object>> propertyVector = createPropertyVector(rs);
		ps.close();
		// If a row has a REALCLASS entry, load data for the subclass
		for (HashMap<String, Object> map : propertyVector)
		{
			Number n = (Number) map.get(Defaults.ID_COL);
			if (n == null)
			{
				continue;
			}
			Long dbId = n.longValue();

			// If a row has a REALCLASS entry, load the subclass
			if (map.get(Defaults.REAL_CLASS_COL) != null)
			{
				// get the real class and id
				String subClassName = (String) map.get(Defaults.REAL_CLASS_COL);
				// get the data for the real class
				HashMap<Class<?>, List<Long>> tmpRes = getObjectDescriptors(cw,null, subClassName, null, dbId);
				for (Entry<Class<?>, List<Long>> en : tmpRes.entrySet())
				{
					Class<?> c = en.getKey();
					List<Long> existingList = res.get(c);
					if (existingList == null)
					{
						res.putAll(tmpRes);
					}
					else
					{
						existingList.addAll(en.getValue());
					}
				}
			}
			else
			{
				// no REAL_CLASS_COL found, which means this is the actual class
				// of the object
				List<Long> idVector = res.get(clazz);
				if (idVector == null)
				{
					idVector = new ArrayList<Long>();
					res.put(clazz, idVector);
				}
				idVector.add(dbId);
			}
		}
		return res;

	}

	/**
	 * Get the last id/unique id inserted into the named table by this
	 * connection
	 * 
	 * @throws SQLException
	 */
	public long getLastId(ConnectionWrapper cw, String tableName) throws SQLException
	{
		long res = 0;
		PreparedStatement ps = cw.prepareStatement(adapter.getLastInsertedIdentity(tableName));
		Tools.logFine(ps);
		ResultSet rs = ps.executeQuery();
		if (rs.next())
		{
			res = rs.getLong(1);
		}
		ps.close();
		return res;
	}
	
	/**
	 * Add an object to the database. If the object already exists, it will be
	 * updated.
	 * 
	 * @param object
	 *            the object to save.
	 * @throws IOException
	 * @throws SQLException
	 * 
	 * @throws SQLException
	 */
	public Long saveObjectUnprotected(ConnectionWrapper cw, Object object) throws SQLException, IOException
	{
		return saveObject(cw, object, false, null);
	}

	/**
	 * Save the object without creating an external protection entry.
	 * 
	 * @param object
	 *            the object to save.
	 * @param cw
	 *            the connection wrapper used for this transaction.
	 * @return the database id of the saved object, or null if the object was
	 *         not saved.
	 * @throws SQLException
	 */
	public Long saveObjectUnprotected(ConnectionWrapper cw, Object object, DelayedInsertionBuffer delayBuffer) throws SQLException
	{
		return saveObject(cw, object, false, delayBuffer);
	}

	public Long saveObject(ConnectionWrapper cw, Object object, boolean protect, DelayedInsertionBuffer delayBuffer) throws SQLException
	{
		Long id = (long) System.identityHashCode(object);
		if (delayBuffer == null)
		{
			delayBuffer = new DelayedInsertionBuffer(adapter.getPersist());
		}
		else if (delayBuffer.isKnown(id, object))
		{
			Long dbId = cache.getDatabaseId(object);
			return dbId;
		}
		delayBuffer.addId(id, object);
		Long res = null;
		String tableName = null;
		if (object.getClass().isArray())
		{
			tableName = NameGenerator.getArrayTablename(adapter);
		}
		else
		{
			tableName = NameGenerator.getTableName(object, adapter);
		}
		// check if the object exists
		Long databaseId = cache.getDatabaseId(object);
		if (databaseId != null && objectExists(cw, object.getClass(), databaseId))
		{
			// the object exists in the database
			res = databaseId;
			if (protect && !protectionManager.isProtectedExternal(tableName, databaseId, cw))
			{
				protectionManager.protectObjectExternal(tableName, databaseId, NameGenerator.getSystemicName(object.getClass()), cw);
			}
			updater.updateObject(cw, object, tableName, databaseId, delayBuffer);
		}
		else
		{
			// the object is unknown
			ObjectStack stack = new ObjectStack(this.adapter, object.getClass(), object, delayBuffer);
			stack.save(cw);
			res = stack.getActualRepresentation().getId();
			// label the object as having been inserted by the outside
			if (protect)
			{
				protectionManager.protectObjectExternal(tableName, res, NameGenerator.getSystemicName(object.getClass()), cw);
			}
		}
		// insert the objects in the delay buffer
		delayBuffer.insertObjects(cw, protectionManager);
		return res;
	}


	/**
	 * Return a list of objects of a given class (including subclasses and/or
	 * implementing classes) that satisfy the given clause.
	 * 
	 * @param <T>
	 *            the type of objects to return
	 * @param clazz
	 *            the class of objects to return, subclasses will also be
	 *            returned.
	 * @param clauses
	 *            the clause that all the returned objects must satisfy.
	 * @param cw
	 *            the connection wrapper to use for this operation.
	 * @return an ArrayList of the desired type.
	 * @throws SQLException
	 */
	@SuppressWarnings("unchecked")
	public <T> List<T> getObjects(ConnectionWrapper cw, Class<T> clazz, Clause... clauses) throws SQLException
	{
		for (Clause clause : clauses)
		{
			clause.setQueryClass(clazz);
		}
		List<T> res = new ArrayList<T>();
		if (!tableManager.tableExists(clazz, cw))
		{
			return res;
		}

		try
		{
			StatementPrototypeGenerator whereGenerator = new StatementPrototypeGenerator(adapter);
			whereGenerator.setClauses(clauses);
			StatementPrototype sp = whereGenerator.generate(clazz, true);
			PreparedStatement ps = sp.toPreparedStatement(cw, sp.getSelectStartQuery());
			ResultSet rs = ps.executeQuery();
			List<HashMap<String, Object>> propertyVector = createPropertyVector(rs);
			ps.close();
			for (HashMap<String, Object> map : propertyVector)
			{
				Long dbId = ((Number) map.get(Defaults.ID_COL)).longValue();
				// If a row has a REALCLASS entry, load the subclass
				if (map.get(Defaults.REAL_CLASS_COL) != null)
				{
					// get the real class
					String className = (String) map.get(Defaults.REAL_CLASS_COL);
					if (className.equalsIgnoreCase(Defaults.ARRAY_TABLENAME))
					{
						// arrays are not loaded in response to WHERE queries,
						// only as members of specific objects.
						continue;
					}
					else
					{
						// load the real class
						clazz = (Class<T>) ClassLoader.getSystemClassLoader().loadClass(className);
						// primitives are not loaded in response to queries,
						// only as
						// parts of other objects
						if (!ObjectTools.isDatabasePrimitive(clazz) && !(clazz.equals(MapEntry.class)) && !(clazz.equals(Number.class)))
						{
							// get the subclass-specific data
							getSubClassData(cw,map, clazz, dbId);
							// load the real class info
							className = (String) map.get(Defaults.REAL_CLASS_COL);
							clazz = (Class<T>) ClassLoader.getSystemClassLoader().loadClass(className);
						}
						else
						{
							continue;
						}
					}
				}
				if (!clazz.isArray())
				{
					String tableName = NameGenerator.getTableName(clazz, adapter);
					// check if the object is known
					Object cachedObject = cache.getObject(tableName, dbId);
					if (cachedObject == null)
					{
						// object was not found in cache
						// create new object
						T nuObject = ObjectFactory.createObject(adapter, cache, map, clazz, cw, tableName, dbId);
						res.add(nuObject);
						// add object to cache
						cache.storeObject(tableName, nuObject, dbId);
					}
					else
					{
						// the object was found, add it to result array
						res.add((T) cachedObject);
					}
				}
			}
		}
		catch (Exception e)
		{
			throw new SQLException(e);
		}
		return res;
	}

	/**
	 * Do a search for objects matching the clazz and clause parameters. Instead
	 * of returning a list like the other getObjects(...) methods, this method
	 * will call the {@link SearchListener#objectFound(Object)} method once for
	 * each object found in the search.
	 * 
	 * 
	 * @param listener
	 *            the object to process the search results.
	 * @param clazz
	 *            the class of returned objects.
	 * @param clause
	 *            the search parameters.
	 * @throws SQLException
	 */
	@SuppressWarnings("unchecked")
	public <T> void getObjects(ConnectionWrapper cw, SearchListener<T> listener, Class<T> clazz, Clause... clauses) throws SQLException
	{
		try
		{
			for (Clause clause : clauses)
			{
				clause.setQueryClass(clazz);
			}
			if (!tableManager.tableExists(clazz, cw))
			{
				return;
			}

			boolean containsOrder = containsOrderStatement(clauses);

			StatementPrototypeGenerator whereGenerator = new StatementPrototypeGenerator(adapter);
			whereGenerator.setClauses(clauses);
			StatementPrototype sp = whereGenerator.generate(clazz, true);
			long currentObject = 0;
			long maxTotalCount = -1;
			if (!containsOrder)
			{
				// there's no "ORDER BY" statement in this query, add one.
				Order order = new Order(new DatabaseIDSorter(clazz));
				// recreate the statement
				whereGenerator = new StatementPrototypeGenerator(adapter);
				Clause[] nuClauses = new Clause[clauses.length + 1];
				System.arraycopy(clauses, 0, nuClauses, 0, clauses.length);
				nuClauses[nuClauses.length - 1] = order;
				whereGenerator.setClauses(nuClauses);
				sp = whereGenerator.generate(clazz, true);
			}
			if (sp.getOffset() != null)
			{
				currentObject = sp.getOffset();
			}
			if (sp.getLimit() != null)
			{
				// user only wants maxTotalCount objects, keep track of this
				// number
				maxTotalCount = sp.getLimit();
			}
			long searchStartedAt = currentObject;
			// alter the search to only return one object at a time
			sp.setLimit(1L);
			while (true)
			{
				sp.setOffset(currentObject);
				// query the database
				PreparedStatement ps = sp.toPreparedStatement(cw, sp.getSelectStartQuery());
				ResultSet rs = ps.executeQuery();
				// get the property map
				List<HashMap<String, Object>> propertyVector = createPropertyVector(rs);
				// close the query
				ps.close();
				boolean found = false;
				for (HashMap<String, Object> map : propertyVector)
				{
					Long dbId = ((Number) map.get(Defaults.ID_COL)).longValue();
					// If a row has a REALCLASS entry, load the subclass
					if (map.get(Defaults.REAL_CLASS_COL) != null)
					{
						// get the real class
						String className = (String) map.get(Defaults.REAL_CLASS_COL);
						if (className.equalsIgnoreCase(Defaults.ARRAY_TABLENAME))
						{
							// arrays are not loaded in response to WHERE
							// queries,
							// only as members of specific objects.
							continue;
						}
						else
						{
							// load the real class
							clazz = (Class<T>) ClassLoader.getSystemClassLoader().loadClass(className);
							// primitives are not loaded in response to queries,
							// only as
							// parts of other objects
							if (!ObjectTools.isDatabasePrimitive(clazz) && !(clazz.equals(MapEntry.class)) && !(clazz.equals(Number.class)))
							{
								// get the subclass-specific data
								getSubClassData(cw,map, clazz, dbId);
								// load the real class info
								className = (String) map.get(Defaults.REAL_CLASS_COL);
								clazz = (Class<T>) ClassLoader.getSystemClassLoader().loadClass(className);
							}
							else
							{
								continue;
							}
						}
					}
					if (!clazz.isArray())
					{
						currentObject++;
						found = true;
						String tableName = NameGenerator.getTableName(clazz, adapter);
						// check if the object is known
						Object cachedObject = cache.getObject(tableName, dbId);
						if (cachedObject == null)
						{
							// object was not found in cache
							// create new object
							T nuObject = ObjectFactory.createObject(adapter, cache, map, clazz, cw, tableName, dbId);
							// add object to cache
							cache.storeObject(tableName, nuObject, dbId);
							// tell listener about object
							listener.objectFound(nuObject);
						}
						else
						{
							// the object was found in cache, pass it to
							// listener
							listener.objectFound((T) cachedObject);
						}
					}
				}
				// no objects found?
				if (!found)
				{
					// search is complete
					break;
				}
				// found enough objects?
				if (maxTotalCount >= 0 && currentObject >= (searchStartedAt + maxTotalCount))
				{
					// search is complete
					break;
				}
			}
		}
		catch (ClassNotFoundException e)
		{
			throw new SQLException(e);
		}
	}


	/**
	 * Get the number of database objects of class clazz that satisfy the
	 * clause.
	 * 
	 * @param <T>
	 * @param clazz
	 * @param clause
	 *            the clause that must be satisfied.
	 * @param cw
	 *            the connection wrapper for this operation.
	 * @return the number of objects of class clazz and its subclasses that
	 *         satisfy clause.
	 * @throws SQLException
	 */
	<T> long getCount(ConnectionWrapper cw, Class<T> clazz, Clause... clause) throws SQLException
	{
		Number[] res = calculateAggregate(cw, clazz, new AggregateFunction[] { new Count() }, clause);
		return (Long) res[0];
	}


	public void saveToCache(String tableName, Object o, Long dbId)
	{
		this.cache.storeObject(tableName, o, dbId);
	}

	/**
	 * Return true if any of the clauses in the argument is an instance of
	 * {@link Order}, false otherwise. Recursively checks subclauses.
	 * 
	 * @param clauses
	 *            the clauses that will be recursively checked for the presence
	 *            of "Order" statements
	 * @return
	 */
	private boolean containsOrderStatement(Clause... clauses)
	{
		if (clauses != null)
		{
			for (Clause c : clauses)
			{
				if (c instanceof Order)
				{
					// stop immediately
					return true;
				}
				if (containsOrderStatement(c.getSubclauses()))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Get the object of class clazz with the given database id. The actual
	 * object returned may be an instance of a subclass.
	 * 
	 * This method does not create a protection entry.
	 * 
	 * @param clazz
	 *            the class of the object to retrieve.
	 * @param id
	 *            the database id of the object at the level of clazz.
	 * @param cw
	 *            the connection wrapper to use for this operation.
	 * @return the matching object.
	 * @throws ClassNotFoundException
	 * @throws SQLException
	 */
	public <T> T getObject(ConnectionWrapper cw, Class<T> clazz, Long id) throws SQLException, ClassNotFoundException
	{
		return getObject(cw, clazz, id, this.cache);
	}

	@SuppressWarnings("unchecked")
	public <T> T getObject(ConnectionWrapper cw, Class<T> clazz, Long id, ObjectRowMap cache) throws SQLException, ClassNotFoundException
	{
		String className = NameGenerator.getSystemicName(clazz);
		T res = null;
		if (!tableManager.tableExists(NameGenerator.getTableName(clazz, adapter), cw))
		{
			return res;
		}

		StatementPrototypeGenerator whereGenerator = new StatementPrototypeGenerator(adapter);
		StatementPrototype sp = whereGenerator.generate(clazz, true);
		String shortName = whereGenerator.getTypeStack().getRepresentation(clazz).getAsName();

		sp.addEqualsClause(shortName + "." + Defaults.ID_COL, id);

		PreparedStatement ps = sp.toPreparedStatement(cw, sp.getSelectStartQuery());
		ResultSet rs = ps.executeQuery();
		List<HashMap<String, Object>> propertyVector = createPropertyVector(rs);
		ps.close();
		for (HashMap<String, Object> map : propertyVector)
		{
			Long dbId = ((Number) map.get(Defaults.ID_COL)).longValue();
			// If a row has a REALCLASS entry, load the subclass
			if (map.get(Defaults.REAL_CLASS_COL) != null)
			{
				// get the real class and id
				className = (String) map.get(Defaults.REAL_CLASS_COL);
				if (!className.equalsIgnoreCase(Defaults.ARRAY_TABLENAME))
				{
					// load the real class
					clazz = (Class<T>) ClassLoader.getSystemClassLoader().loadClass(className);
					// get the subclass-specific data
					getSubClassData(cw,map, clazz, dbId);
					// load the real class info
					className = (String) map.get(Defaults.REAL_CLASS_COL);
				}
			}

			// check if the object is an array
			if (clazz.isArray() || className.equalsIgnoreCase(Defaults.ARRAY_TABLENAME))
			{

				// the referenced object is an array, so load it
				Object cachedObject = cache.getObject(NameGenerator.getArrayTablename(adapter), dbId);

				if (cachedObject == null)
				{
					// object was not found in cache
					// create new array object
					ArrayLoader arrayLoader = new ArrayLoader(this.adapter, cache, cw);
					arrayLoader.loadArray(dbId);
					T nuObject = (T) arrayLoader.getArray();
					res = nuObject;
				}
				else
				{
					// the object was found, add it to result array
					res = (T) cachedObject;
				}
			}
			else
			{
				// load an ordinary object
				// check if the object is known
				clazz = (Class<T>) ClassLoader.getSystemClassLoader().loadClass(className);
				String tableName = NameGenerator.getTableName(clazz, adapter);
				Object cachedObject = cache.getObject(tableName, dbId);
				if (cachedObject == null)
				{
					// object was not found in cache
					// create new object
					res = ObjectFactory.createObject(adapter, cache, map, clazz, cw, tableName, dbId);
				}
				else
				{
					// the object was found, add it to result array
					res = (T) cachedObject;
				}
			}
		}
		return res;
	}

	/**
	 * Checks the database to see if an object of the given class with the given
	 * C__ID value exists. If the corresponding table does not exist, the method
	 * return false.
	 * 
	 * @param cw
	 *            a connection wrapper to use.
	 * @param clazz
	 *            the object class to search
	 * @param id
	 *            the C__ID value to check for - this is the database id.
	 * 
	 * @return true if the object exists, false otherwise.
	 * 
	 * @throws SQLException
	 */
	public <T> boolean objectExists(ConnectionWrapper cw, Class<T> clazz, Long id) throws SQLException
	{
		boolean res = false;
		String tableName = NameGenerator.getTableName(clazz, adapter);
		if (!tableManager.tableExists(tableName, cw))
		{
			return res;
		}

		StringBuilder statement = new StringBuilder("SELECT COUNT(*) FROM ");
		statement.append(tableName);
		statement.append(" WHERE ");
		statement.append(Defaults.ID_COL);
		statement.append(" = ?");

		PreparedStatement ps = cw.prepareStatement(statement.toString());
		ps.setLong(1, id);
		Tools.logFine(ps);
		ResultSet rs = ps.executeQuery();
		if (rs.next() && rs.getLong(1) > 0)
		{
			res = true;
		}
		ps.close();
		return res;
	}

	/**
	 * Get a list of all classes persisted in this database. It does not include
	 * classes representing primitives, e.g. java.lang.Integer, or array
	 * classes.
	 * 
	 * @return a list of classes.
	 * @throws SQLException
	 */
	public List<Class<?>> getClasses() throws SQLException
	{
		ArrayList<Class<?>> res = new ArrayList<Class<?>>();
		// get all c__IS_A entries
		String selectStmt = "SELECT * from " + Defaults.IS_A_TABLENAME;
		ConnectionWrapper cw = getConnectionWrapper();
		try
		{
			PreparedStatement ps = cw.prepareStatement(selectStmt);
			ResultSet rs = ps.executeQuery();
			while (rs.next())
			{
				String superClassName = rs.getString("SUPERCLASS");
				String subClassName = rs.getString("SUBCLASS");
				try
				{
					Class<?> superClass = ObjectTools.lookUpClass(superClassName, adapter);
					if (superClass != null && !res.contains(superClass))
					{
						res.add(superClass);
					}
				}
				catch (ClassNotFoundException e)
				{
					LOGGER.log(Level.WARNING, "ClassNotFoundException: ", e);
				}
				try
				{
					Class<?> subClass = ObjectTools.lookUpClass(subClassName, adapter);
					if (subClass != null && !res.contains(subClass))
					{
						res.add(subClass);
					}
				}
				catch (ClassNotFoundException e)
				{
					LOGGER.log(Level.WARNING, "ClassNotFoundException: ", e);
				}

			}
			ps.close();
			cw.commitAndDiscard();
		}
		catch (Exception e)
		{
			cw.rollbackAndDiscard();
			throw new SQLException(e);
		}

		return res;
	}

	/**
	 * Recursively descend the inheritance tree, loading all properties.
	 * 
	 * @param <T>
	 * @param map
	 *            a property-value map of the object to get
	 * @param clazz
	 *            the class to look up values for.
	 * @param dbId
	 *            the unique identifier in the database table.
	 * @return
	 * @throws ClassNotFoundException
	 * @throws SQLException
	 */
	@SuppressWarnings("unchecked")
	private <T> void getSubClassData(ConnectionWrapper cw, HashMap<String, Object> map, Class<T> clazz, Long dbId)
			throws ClassNotFoundException, SQLException
	{
		String realClassName = (String) map.get(Defaults.REAL_CLASS_COL);
		// erase the 'real' class entry
		map.remove(Defaults.REAL_CLASS_COL);
		StringBuilder statement = new StringBuilder("SELECT * FROM ");
		StatementPrototypeGenerator whereGenerator = new StatementPrototypeGenerator(adapter);
		StatementPrototype sp = whereGenerator.generate(clazz, false);
		sp.addEqualsClause(Defaults.ID_COL, dbId);
		PreparedStatement ps = sp.toPreparedStatement(cw, statement.toString());
		ResultSet rs = ps.executeQuery();
		List<HashMap<String, Object>> propertyVector = createPropertyVector(rs);
		if (propertyVector.size() != 1)
		{
			throw new SQLException("Wrong number of subclass entities found: " + propertyVector.size() + ", expected 1.");
		}
		ps.close();
		HashMap<String, Object> subMap = propertyVector.get(0);
		// merge the two maps
		map.putAll(subMap);
		// check if we need to recurse down the class hierarchy
		String subClassName = (String) map.get(Defaults.REAL_CLASS_COL);
		if (subClassName != null)
		{
			Class<?> subClass = (Class<T>) ClassLoader.getSystemClassLoader().loadClass(subClassName);
			getSubClassData(cw,map, subClass, dbId);
		}
		else
		{
			// otherwise, put the real class name back
			map.put(Defaults.REAL_CLASS_COL, realClassName);
		}
	}

	/**
	 * Create a mapping from table/member names to values, based on the rows
	 * returned from the ResultSet.
	 * 
	 * @param rs
	 * @return
	 * @throws SQLException
	 */
	private List<HashMap<String, Object>> createPropertyVector(ResultSet rs) throws SQLException
	{
		ArrayList<HashMap<String, Object>> res = new ArrayList<HashMap<String, Object>>();
		whileLoop: while (rs.next())
		{
			HashMap<String, Object> map = new HashMap<String, Object>();
			fillValues(rs, map);
			if (!adapter.handlesDistinctWithClobsAndBlobsCorrectly())
			{
				// the DB engine we are using does not handle DISTINCT(...) in
				// conjunction with Clobs and/or Blobs, so we are forced to
				// manually check that we don't insert double entries.
				Long nuId = (Long) map.get(Defaults.ID_COL);
				for (HashMap<String, Object> tmpMap : res)
				{
					Long existingId = (Long) tmpMap.get(Defaults.ID_COL);
					if (nuId.equals(existingId))
					{
						// do not insert the new map, as it currently already
						// exists.
						continue whileLoop;
					}
				}
				// if we got this far without finding a duplicate map, insert
				// the new map
				res.add(map);

			}
			else
			{
				res.add(map);
			}
		}
		return res;
	}

	/**
	 * Fill the set values of the result set into the HashMap. The ResultSet
	 * will not be advanced beyond its current row.
	 * 
	 * @param rs
	 * @param map
	 * @throws SQLException
	 */
	private void fillValues(ResultSet rs, HashMap<String, Object> map) throws SQLException
	{

		ResultSetMetaData md = rs.getMetaData();
		for (int x = 0; x < md.getColumnCount(); x++)
		{
			// for all the column names, set the corresponding property
			String key = md.getColumnName(x + 1);
			if (adapter.getTableNamesAreLowerCase())
			{
				key = key.toUpperCase();
			}
			if (map.get(key) == null)
			{
				Object o = rs.getObject(x+1);
				//check if the database has converted a tinyint into a string out of sheer stupidity
				if(md.getColumnType(x+1)==Types.TINYINT && o instanceof String)
				{
					//we must be dealing with a poorly implemented driver
					o = Short.parseShort(o.toString());
				}
				map.put(key, o);
			}
		}
	}

	void close()
	{
		connectionPool.cleanUp();
		cache.stop();
	}

	public AdapterBase getAdapter()
	{
		return this.adapter;
	}

	public TableManager getTableManager()
	{
		return this.tableManager;
	}

	public ProtectionManager getProtectionManager()
	{
		return this.protectionManager;
	}

	/**
	 * Get the id of the given object.
	 * 
	 * @param o
	 *            the object to get the id for.
	 * @return the database id of component, if it is cached.
	 */
	public Long getId(Object o)
	{
		return cache.getDatabaseId(o);
	}

	public ArrayEntryWriter getArrayEntryWriter()
	{
		return arrayEntryWriter;
	}

	/**
	 * Recursively descend a dependency tree until the bottom is reached. This
	 * method looks for the ultimate subclass of an object and returns its name
	 * and the corresponding id.
	 * 
	 * @param cw
	 *            the ConnectionWrapper to use - will be passed on recursively.
	 * @param propertyClass
	 *            the class to look for subclasses of.
	 * @param propertyId
	 *            the database id corresponding to propertyClass.
	 * @return the name of the table describing the ultimate subclass, and the
	 *         id value for that table.
	 * @throws SQLException
	 */
	public ClassIdTuple getRealTableNameAndId(ConnectionWrapper cw, Class<?> propertyClass, Long propertyId) throws SQLException
	{
		ClassIdTuple res = null;
		String propertyTable = NameGenerator.getTableName(propertyClass, adapter);
		StringBuilder query = new StringBuilder("SELECT ");
		query.append(Defaults.REAL_CLASS_COL);
		query.append(" FROM ");
		query.append(propertyTable);
		query.append(" WHERE ");
		query.append(Defaults.ID_COL);
		query.append("=?");
		PreparedStatement ps = cw.prepareStatement(query.toString());
		ps.setLong(1, propertyId);
		ResultSet rs = ps.executeQuery();
		if (rs.next())
		{
			String realName = rs.getString(1);
			if (realName == null)
			{
				res = new ClassIdTuple(propertyClass, propertyId);
			}
			else
			{
				try
				{
					// convert to table name
					Class<?> realClass = ObjectTools.lookUpClass(realName, adapter);
					res = getRealTableNameAndId(cw, realClass, propertyId);
				}
				catch (ClassNotFoundException e)
				{
					throw new SQLException(e);
				}
			}

		}
		else
		{
			ps.close();
			throw new SQLException("Could not find entry in " + propertyTable + " with id " + propertyId);
		}
		ps.close();
		return res;
	}

	/**
	 * Refresh an object. Get all the data in the object from the database,
	 * ignoring the cache.
	 * 
	 * Objects that were, but are no longer, part of obj are not updated.
	 * 
	 * @param obj
	 *            the object to refresh.
	 * @param cw
	 *            the connection wrapper.
	 * @return the refreshed object or null if the object is no longer in the
	 *         database.
	 * @throws SQLException
	 */
	<T> T refresh(ConnectionWrapper cw, T obj) throws SQLException
	{

		try
		{
			T res = obj;
			Long dbId = cache.getDatabaseId(obj);
			if(dbId == null)
			{
				throw new IllegalArgumentException("Could not find object in cache");
			}
			ObjectRowMap tmpCache = new ObjectRowMap();
			tmpCache.start();
			Object nuObject = this.getObject(cw, obj.getClass(), dbId, tmpCache);
			if (nuObject == null)
			{
				res = null;
			}
			else
			{
				ObjectRepresentation orig = new ConcreteObjectRepresentation(adapter, obj.getClass(), obj, null);
				ObjectRepresentation nu = new ConcreteObjectRepresentation(adapter, nuObject.getClass(), nuObject, null);
				ArrayList<Long> idList = new ArrayList<Long>();
				refresh(orig, cache, nu, tmpCache, idList);
			}
			tmpCache.stop();
			return res;
		}
		catch (IllegalAccessException | InvocationTargetException | ClassNotFoundException e)
		{
			throw new SQLException(e);
		}
	}

	private void refresh(ObjectRepresentation orig, ObjectRowMap origCache, ObjectRepresentation nu, ObjectRowMap nuCache, ArrayList<Long> idList)
			throws IllegalArgumentException, IllegalAccessException, InvocationTargetException
	{
		for (int x = 0; x < orig.getPropertyCount(); x++)
		{
			Object origProperty = orig.getPropertyValue(x);
			Object nuProperty = nu.getPropertyValue(x);
			if (origProperty == null)
			{
				if (nuProperty != null)
				{
					orig.setPropertyValue(x, nuProperty);
					if(orig.isReferenceType(x))
					{
						origCache.storeObject(orig.getTableName(), nuProperty, nuCache.getDatabaseId(nuProperty));
					}
				}
			}
			else if (nuProperty == null)
			{
				// property has been deleted, remove reference
				orig.setPropertyValue(x, null);
			}
			else
			{
				// both origProperty and nuProperty are non-null
				if (orig.isPrimitive(x))
				{
					if (origProperty.equals(nuProperty))
					{
						// the id, and therefore the objects, are unchanged.
						// no point in recursing into primitives
					}
					else
					{
						orig.setPropertyValue(x, nuProperty);
					}
				}
				else
				{
					Long origDbId = origCache.getDatabaseId(origProperty);
					Long nuDbId = nuCache.getDatabaseId(nuProperty);
					if (origDbId.equals(nuDbId))
					{
						// the id, and therefore the objects, are unchanged.
						// recurse
						ObjectRepresentation origPropertyPresentation = new ConcreteObjectRepresentation(adapter, origProperty.getClass(),
								origProperty, null);
						ObjectRepresentation nuPropertyPresentation = new ConcreteObjectRepresentation(adapter, nuProperty.getClass(), nuProperty,
								null);
						if (!idList.contains(origDbId))
						{
							idList.add(origDbId);
							refresh(origPropertyPresentation, origCache, nuPropertyPresentation, nuCache, idList);
						}
					}
					else
					{
						orig.setPropertyValue(x, nuProperty);
						origCache.storeObject(orig.getTableName(), nuProperty, nuDbId);
					}
				}
			}
		}
	}

	/**
	 * Clone this database into another database.
	 * 
	 * After this method completes successfully, the target database will
	 * contain an exact copy of this database. Schema and contents will be
	 * copied, but there is no guarantee that the C__ID fields will be the same.
	 * If this database or the target database is written to during this
	 * operation, the outcome is undefined.
	 * 
	 * If this operation fails the state of the target database is undefined.
	 * 
	 * This operation does not interfere with existing objects in the target
	 * database.
	 * 
	 * This operation does not change the state of this database.
	 * 
	 * @param target
	 *            the target database.
	 * @throws SQLException
	 * 
	 */
	void duplicate(Persist target) throws SQLException
	{
		// basic sanity check
		if (this == target)
		{
			throw new IllegalArgumentException("Can not copy database into itself.");
		}
		Duplicator dup = new Duplicator(this, target);
		dup.doCopy();
	}

	/**
	 * Get the cache of objects currently loaded from the DB into memory.
	 * 
	 * @return a reference to the local cache.
	 */
	public ObjectRowMap getCache()
	{
		return this.cache;
	}

	/**
	 * Get the result of all the aggregate functions of all the named fields
	 * that match all clauses.
	 * 
	 * @param <T>
	 * 
	 * @param cw
	 *            the connection to use for the query.
	 * @param clazz
	 *            the class to calculate aggregate functions on, can be an
	 *            interface.
	 * @param functions
	 *            the aggregate functions to calculate
	 * @param where
	 * @return an array of appropriate Number sublasses (Double, Integer, Long,
	 *         Short, Byte, Float).
	 * @throws SQLException
	 */
	public <T> Number[] calculateAggregate(ConnectionWrapper cw, Class<T> clazz, AggregateFunction[] functions, Clause[] where) throws SQLException
	{
		Number[] res = null;
		try
		{
			// create an array to hold the results
			res = new Number[functions.length];

			// make sure the class exists
			if (tableManager.tableExists(clazz, cw))
			{

				// generate the WHERE part of the statement
				StatementPrototypeGenerator whereGenerator = new StatementPrototypeGenerator(adapter);
				whereGenerator.setClauses(where);
				StatementPrototype sp = whereGenerator.generate(clazz, true);

				// generate the SELECT part of the statement
				StringBuilder selection = new StringBuilder("SELECT ");
				for (int x = 0; x < functions.length; x++)
				{
					AggregateFunction af = functions[x];
					selection.append(af.getStringRepresentation(whereGenerator.getTypeStack()));
					if (x < functions.length - 1)
					{
						selection.append(",");
					}
				}
				selection.append(" FROM ");

				// generate query
				PreparedStatement ps = sp.toPreparedStatement(cw, selection.toString());

				// execute query
				ResultSet rs = ps.executeQuery();

				// parse results
				if (rs.next())
				{
					for (int x = 0; x < functions.length; x++)
					{

						res[x] = getValueFromQuery(rs, functions[x], whereGenerator.getTypeStack(), x + 1);
					}
				}

				ps.close();
			}
			else
			{
				ObjectStack stack = new ObjectStack(adapter, clazz);
				// the class does not exist
				// fill in res with values that make sense
				for (int x = 0; x < functions.length; x++)
				{
					Class<? extends Number> n = functions[x].getReturnType(stack);
					if (functions[x] instanceof Count)
					{
						// count is always Long
						res[x] = new Long(0);
					}
					else if (functions[x] instanceof Sum)
					{
						//sum is always either Long or Double.
						if (n.equals(Double.class))
						{
							res[x] = new Double(0);
						}
						else
						{
							res[x] = new Long(0);
						}
					}
					else
					{
						// average, max, and min of an empty set does not make
						// sense
						if (n.equals(Float.class) || n.equals(float.class))
						{
							res[x] = Float.NaN;
						}
						else if (n.equals(Double.class) || n.equals(double.class))
						{
							res[x] = Double.NaN;
						}
						else
						{
							// no way to represent NaN in integer classes, leave
							// as
							// null and hope calling code knows how to handle
							// this
						}
					}
				}
			}
		}
		catch (NoSuchMethodException | SecurityException e)
		{
			throw new SQLException(e);
		}
		return res;
	}

	/**
	 * @param rs
	 * @param aggregateFunction
	 * @param i
	 * @return
	 * @throws SecurityException
	 * @throws NoSuchMethodException
	 * @throws SQLException
	 */
	private Number getValueFromQuery(ResultSet rs, AggregateFunction function, ObjectStack stack, int column)
			throws NoSuchMethodException, SecurityException, SQLException
	{
		Number res = null;
		Class<?> number = function.getReturnType(stack);
		// get the smallest value
		if (number == Long.class || number == long.class)
		{
			res = rs.getLong(column);
		}
		else if (number == Integer.class || number == int.class)
		{
			res = rs.getInt(column);
		}
		else if (number == Short.class || number == short.class)
		{
			res = rs.getShort(column);
		}
		else if (number == Byte.class || number == byte.class)
		{
			res = rs.getByte(column);
		}
		else if (number == Double.class || number == double.class)
		{
			res = rs.getDouble(column);
		}
		else if (number == Float.class || number == float.class)
		{
			res = rs.getFloat(column);
		}
		else if (number == Character.class || number == char.class)
		{
			//characters aren't numbers, so get the integer instead
			res = rs.getInt(column);
		}
		else
		{
			throw new SQLException("Don't know how to " + function.getStringRepresentation(stack) + " as " + number);
		}
		return res;
	}

}
