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
package com.github.conserveorm.adapter;

import java.lang.reflect.Method;
import java.sql.Blob;
import java.sql.Clob;
import java.util.Properties;

import com.github.conserveorm.Persist;
import com.github.conserveorm.annotations.Indexed;
import com.github.conserveorm.annotations.MaxLength;
import com.github.conserveorm.annotations.MultiIndexed;
import com.github.conserveorm.tools.Defaults;
import com.github.conserveorm.tools.generators.NameGenerator;

/**
 * Base adapter. This class is the base for all adapters. Adapters are objects
 * that generate native SQL code intended for a given database.
 * 
 * The base adapter should be adequate for almost all situations. Only override
 * this class if you are connecting to a database that requires non-standard
 * syntax.
 * 
 * @author Erik Berglund
 * 
 */
public class AdapterBase
{
	private Persist persist;

	public AdapterBase(Persist persist)
	{
		this.persist = persist;
	}

	/**
	 * Return the field type of a given class.
	 * 
	 * @param c
	 *            the class.
	 * @param m
	 *            the method that returns the object.
	 * @return a String representation of the SQL data type of the column
	 *         representation of the given class.
	 */
	public String getColumnType(Class<?> c, Method m)
	{

		if (c.equals(boolean.class) || c.equals(Boolean.class))
		{
			return getBooleanTypeKeyword();
		}
		else if (c.equals(byte.class) || c.equals(Byte.class))
		{
			return getByteTypeKeyword();
		}
		else if (c.equals(short.class) || c.equals(Short.class))
		{
			return getShortTypeKeyword();
		}
		else if (c.equals(char.class) || c.equals(Character.class))
		{
			return getCharacterTypeKeyword();
		}
		else if (c.equals(int.class) || c.equals(Integer.class))
		{
			return getIntegerTypeKeyword();
		}
		else if (c.equals(long.class) || c.equals(Long.class))
		{
			return getLongTypeKeyword();
		}
		else if (c.equals(float.class) || c.equals(Float.class))
		{
			return getFloatTypeKeyword();
		}
		else if (c.equals(double.class) || c.equals(Double.class))
		{
			return getDoubleTypeKeyword();
		}
		else if (c.equals(String.class) || c.isEnum() || c.equals(Class.class))
		{
			if (m != null && m.isAnnotationPresent(MaxLength.class))
			{
				long length = m.getAnnotation(MaxLength.class).value();
				return getVarCharKeyword(length);
			}
			else
			{
				if(m != null && (m.isAnnotationPresent(Indexed.class) || m.isAnnotationPresent(MultiIndexed.class)))
				{
					return getVarCharIndexed();
				}
				else
				{
					return getVarCharKeyword();
				}
			}
		}
		else if (c.equals(java.sql.Date.class))
		{
			return getDateKeyword();
		}
		else if (c.equals(java.sql.Time.class))
		{
			return getTimeKeyword();
		}
		else if (c.equals(java.sql.Timestamp.class))
		{
			return getTimestampKeyword();
		}
		else if (c.equals(Clob.class))
		{
			return getClobKeyword();
		}
		else if (c.equals(Blob.class))
		{
			return getBlobTypeKeyword();
		}
		else
		{
			// handle all other objects by inserting a reference
			return getReferenceType(c);
		}
	}

	/**
	 * Get the keyword that indicates a column holds dates.
	 * 
	 * @return
	 */
	private String getDateKeyword()
	{
		return "DATE";
	}

	/**
	 * Get the keyword that indicates a column holds times.
	 * 
	 * @return
	 */
	private String getTimeKeyword()
	{
		return "TIME";
	}

	/**
	 * Get the keyword that indicates a column holds timestamps.
	 * 
	 * @return
	 */
	private String getTimestampKeyword()
	{
		return "TIMESTAMP";
	}

	public String getByteTypeKeyword()
	{
		return "TINYINT";
	}

	/**
	 * Get the SQL keyword that represents single-precision floating point
	 * numbers.
	 * 
	 * @return an SQL keyword.
	 */
	public String getFloatTypeKeyword()
	{
		return "FLOAT";
	}

	/**
	 * Get the SQL keyword that represents 64-bit (or larger) integers.
	 * 
	 * @return The keyword that indicates a 64-bit (or larger) integer.
	 */
	public String getLongTypeKeyword()
	{
		return "BIGINT";
	}

	/**
	 * Get the SQL keyword that represents double-precision floating point
	 * numbers.
	 * 
	 * @return an SQL keyword.
	 */
	public String getDoubleTypeKeyword()
	{
		return "DOUBLE";
	}

	/**
	 * Get the SQL keyword that represents a column with one single-precision
	 * integer.
	 * 
	 * @return the keyword used to indicate a type compatible with
	 *         java.lang.Integer.
	 */
	public String getIntegerTypeKeyword()
	{
		return "INTEGER";
	}

	/**
	 * Get the SQL keyword that represents a column with one character data.
	 * 
	 * @return the keyword used to indicate a type compatible with
	 *         java.lang.Character.
	 */
	public String getCharacterTypeKeyword()
	{
		return getIntegerTypeKeyword();
	}

	/**
	 * Get the SQL keyword that represents one byte.
	 * 
	 * @return the keyword used to indicate a type compatible with
	 *         java.lang.Byte.
	 */
	public String getShortTypeKeyword()
	{
		return getByteTypeKeyword();
	}

	public Persist getPersist()
	{
		return persist;
	}

	/**
	 * Define how objects of another class will be referenced
	 * 
	 * @param c
	 *            the class to reference.
	 * @return a string describing how objects of the given class will be
	 *         referenced.
	 */
	public String getReferenceType(Class<?> c)
	{
		if (c.isArray())
		{
			return getReferenceType(Defaults.ARRAY_TABLENAME);
		}
		else
		{
			return getReferenceType(NameGenerator.getTableName(c, this));
		}
	}

	/**
	 * Define how another table will be referenced
	 * 
	 * @param tableName
	 *            the name of the table to reference.
	 * @return a string describing how rows in the given table will be
	 *         referenced.
	 */
	public String getReferenceType(String tableName)
	{
		return getLongTypeKeyword();
	}

	/**
	 * Return a string describing the SQL varchar column definition.
	 * 
	 */
	public String getVarCharKeyword()
	{
		return "VARCHAR";
	}

	/**
	 * Return a string describing the SQL varchar column definition, with a
	 * defined length.
	 * 
	 * 
	 * @param length
	 * @return the VARCHAR keyword, with appropriate length modifier.
	 */
	public String getVarCharKeyword(long length)
	{
		return "VARCHAR(" + Long.toString(length) + ")";
	}

	/**
	 * Some SQL implementations demand that a key length is specified. If so,
	 * override this method and indicate it.
	 * 
	 * @return a parenthesis-enclosed numerical value (e.g. "(500)") or an empty
	 *         string.
	 */
	public String getKeyLength()
	{
		return "";
	}

	/**
	 * Get whether triggers are supported.
	 * 
	 * @return true if triggers are supported, false otherwise.
	 */
	public boolean isSupportsTriggers()
	{
		return true;
	}

	/**
	 * Get whether auto-incrementing columns are supported.
	 * 
	 * @return true if auto-increment is supported, false otherwise.
	 */
	public boolean isSupportsIdentity()
	{
		return true;
	}

	/**
	 * Get the string used during table creation to indicate that a column
	 * should auto-increment.
	 * 
	 * @return a string.
	 */
	public String getIdentity()
	{
		return "BIGINT IDENTITY";
	}

	/**
	 * Get the string that, along with parentheses, is used to get the last
	 * added auto-incrementing id.
	 * 
	 * @return the name of the function without parentheses.
	 */
	public String getLastInsertedIdentity(String tableName)
	{
		return "SELECT IDENTITY()";
	}

	/**
	 * Check if a name can be used as column name.
	 */
	public boolean isValidColumnName(String name)
	{
		// default is allow all.
		return true;
	}

	/**
	 * Check if database supports the EXISTS keyword.
	 */
	public boolean isSupportsExistsKeyword()
	{
		// default is to support the EXISTS keyword.
		return true;
	}

	/**
	 * Return true if the database internally handles column names as lower
	 * case.
	 * 
	 * @return true if TABLE_NAME should be converted to table_name before use.
	 */
	public boolean getTableNamesAreLowerCase()
	{
		return false;
	}

	/**
	 * True return values indicate that a transaction must be committed after
	 * creating/altering a table and before a table can be used.
	 * 
	 * The default is false;
	 * 
	 * @return true or false;
	 */
	public boolean isRequiresCommitAfterSchemaAlteration()
	{
		return false;
	}

	/**
	 * Get the keyword used in JOIN-statements.
	 * 
	 * @return the JOIN keyword.
	 */
	public Object getJoinKeyword()
	{
		return ",";
	}

	/**
	 * Returns true iff this database handles insert statements of the type
	 * "insert into TABLE () values ()", false otherwise.
	 * 
	 * @return true if empty insert statements can be used.
	 */
	public boolean getAllowsEmptyStatements()
	{
		return true;
	}

	/**
	 * Return true if the Character Large OBject (CLOB) data type is fully
	 * supported. Default is true.
	 * 
	 * @return true if CLOB is supported.
	 */
	public boolean isSupportsClob()
	{
		return true;
	}

	/**
	 * Get the keyword used to denote a CLOB type.
	 * 
	 * @return the CLOB keyword.
	 */
	public String getClobKeyword()
	{
		return "CLOB";
	}

	/**
	 * Return true if the Binary Large OBject (BLOB) data type is fully
	 * supported. Default is true.
	 * 
	 * @return true if BLOB is supported.
	 */
	public boolean isSupportsBlob()
	{
		return true;
	}

	/**
	 * Get the keyword used to denote a BLOB type.
	 * 
	 * @return the BLOB keyword.
	 */
	public String getBlobTypeKeyword()
	{
		return "BLOB";
	}


	/**
	 * Get the varchar for indexed varchar arrays, as this may be different from
	 * the max size of non-indexed varchars.
	 * 
	 * @return the definition for varchar columns.
	 */
	public String getVarCharIndexed()
	{
		return getVarCharKeyword();
	}

	/**
	 * Return whether the database supports the LIMIT and OFFSET keywords. These
	 * are convenience keywords, and non-complying databases will be assumed to
	 * use the more verbose ROW_NUM method. The default is true, since most
	 * databases support these keywords.
	 * 
	 * @return true if the database supports LIMIT and OFFSET, false otherwise.
	 */
	public boolean isSupportsLimitOffsetKeywords()
	{
		return true;
	}

	/**
	 * Get the string to append to the query to limit the number of returned
	 * rows. The ? will be replaced with the actual limit.
	 * 
	 * @return the query return limit/fetch string.
	 */
	public String getLimitString()
	{
		return "LIMIT ?";
	}

	/**
	 * Get the string to append to the query to skip a number of returned rows.
	 * The ? will be replaced with the actual number of rows to skip.
	 * 
	 * @return the query return offset string.
	 */
	public String getOffsetString()
	{
		return "OFFSET ?";
	}

	/**
	 * Do we put LIMIT/FETCH or OFFSET first?
	 * 
	 * @return true if LIMIT/FETCH goes before OFFSET.
	 */
	public boolean isPutLimitBeforeOffset()
	{
		return true;
	}

	/**
	 * Do we put limit/offset before the list of columns?
	 * 
	 * @return true if "select limit n offset m col1, col2, where... is the
	 *         correct form, false otherwise.
	 */
	public boolean isPutLimitOffsetBeforeColumns()
	{
		return false;
	}

	/**
	 * Return true if we can use SELECT
	 * DISTINCT(NON_BLOB_OR_CLOB_COLUMN),BLOB_OR_CLOB_COlUMN ... in queries,
	 * false otherwise.
	 */
	public boolean handlesDistinctWithClobsAndBlobsCorrectly()
	{
		return true;
	}

	/**
	 * Get the keyword used to indicate that a column is to store values of type
	 * boolean.
	 * 
	 */
	public String getBooleanTypeKeyword()
	{
		return "BOOLEAN";
	}

	/**
	 * Return the maximum length of a table, procedure, index, or sequence name.
	 * 
	 * @return Get the maximum length of a name.
	 */
	public int getMaximumNameLength()
	{
		return Integer.MAX_VALUE;
	}


	/**
	 * Return true if the underlying database engine can rename a column.
	 * 
	 * @return false if column renaming is not supported.
	 */
	public boolean canRenameColumn()
	{
		return true;
	}

	/**
	 * Create an SQL statement that will rename a column. The table name, old
	 * column name, and new column name are placeholders.
	 * 
	 * @return the SQL statement used to rename a column.
	 */
	public String getRenameColumnStatement()
	{
		StringBuilder statement = new StringBuilder("ALTER TABLE ");
		statement.append(Defaults.TABLENAME_PLACEHOLDER);
		statement.append(" ALTER COLUMN ");
		statement.append(Defaults.OLD_COLUMN_NAME_PLACEHOLDER);
		statement.append(" RENAME TO ");
		statement.append(Defaults.NEW_COLUMN_NAME_PLACEHOLDER);
		return statement.toString();
	}


	/**
	 * Get the statement to rename a table.
	 * 
	 * @param oldTableName
	 *            the name of the table to rename.
	 * @param oldClass the old class description
	 * @param newTableName
	 *            the new name of the table.
	 * @param newClass the new class description
	 */
	public String[] getTableRenameStatements(String oldTableName, Class<?> oldClass, String newTableName, Class<?>newClass)
	{
		StringBuilder sb = new StringBuilder("ALTER TABLE ");
		sb.append(oldTableName);
		sb.append(" RENAME TO ");
		sb.append(newTableName);
		return new String[]{sb.toString()};

	}

	/**
	 * If the underlying database can change the type of a column, return true.
	 * Otherwise, return false. This method also returns true if the underlying
	 * database can perform some, but not all, column type changes, as long as
	 * the allowed changes include all changes used by Conserve.
	 * 
	 * 
	 * @return true if the database engine natively supports changing the type of existing columns.
	 */
	public boolean canChangeColumnType()
	{
		return true;
	}

	/**
	 * Check if the underlying database allows us to drop a column.
	 * @return true if the database engine natively supports dropping a column, false otherwise.
	 */
	public boolean canDropColumn()
	{
		return true;
	}

	/**
	 * Get the statements for dropping an index from a table.
	 * 
	 * @param table the table to drop the index from
	 * @param indexName the index to drop.
	 * @return an array of statements that, collectively, will drop the named index.
	 */
	public String[] getDropIndexStatements(String table, String indexName)
	{
		StringBuilder sb = new StringBuilder("DROP INDEX ");
		if(isSupportsExistsKeyword())
		{
			sb.append("IF EXISTS ");
		}
		sb.append(indexName);
		return new String[]{ sb.toString()};
	}

	/**
	 * @return true if the underlying database can handle joins in UPDATE statements.
	 */
	public boolean isSupportsJoinInUpdate()
	{
		return true;
	}

	/**
	 * @return true if the AVG() function requires integer types to be cast to floats, false otherwise.
	 */
	public boolean averageRequiresCast()
	{
		return true;
	}

	/**
	 * @return the keyword used to alter column definitions.
	 */
	public Object getColumnModificationKeyword()
	{
		return "ALTER COLUMN";
	}

	/**
	 * Does the column modification statement require a special keyword between the column name and the type?
	 * If so, return it here.
	 * @return the string to go between a column name and the type identifier.
	 */
	public Object getColumnModificationTypeKeyword()
	{
		return "";
	}

	/**
	 * For most databases renaming a table preserves indices. 
	 * If this is not the case, the indices must be recreated and this method returns true.
	 * @return true if we must recreate indices after renaming a table.
	 */
	public boolean indicesMustBeRecreatedAfterRename()
	{
		return false;
	}

	/**
	 * Indicate whether the underlying engine can change the name of an existing table.
	 * 
	 * This may return true for database engines that can create a new table and copy values across 
	 * via a few static commands, but will return false if dynamic or complex commands are needed.
	 * 
	 * 
	 * @return true if the database engine natively supports table renaming.
	 */
	public boolean canRenameTable()
	{
		return true;
	}

	/**
	 * Returns the statement to check if a sequence (generator) exists.
	 * On most database engines sequences are not used because they support incrementing columns,
	 * in which case the default implementation will just return null.
	 * 
	 * @see AdapterBase#isSupportsIdentity()
	 * 
	 * @param sequenceName the named sequence we want to remove.
	 * @return a statement that will remove the named sequence.
	 */
	public String getSequenceExistsStatement(String sequenceName)
	{
		//the default implementation is to return null.
		return null;
	}

	/**
	 * Some database engines don't automatically drop indices when a table is dropped, which can waste space and will create problems
	 * when the table is recreated after a rename.
	 * @return true if we must manually drop indices after a table is dropped.
	 */
	public boolean indicesMustBeManuallyDropped()
	{
		return false;
	}

	/**
	 * Get the maximum allowed value for the C__ID column. 
	 * This is also the maximum number of objects that can be stored in the database.
	 * 
	 * @return the highest possible database ID.
	 */
	public long getMaximumIdNumber()
	{
		//default implementation is 2^63-1
		return Long.MAX_VALUE;
	}
	
	/**
	 * The maximum number of matching values in an SQL statement.
	 * Most database engines do not have a hard limit, so the default implementation simply 
	 * returns null.
	 * @return the maximum number of matching values.
	 */
	public Integer getMaxMatchingValues()
	{
		return null;
	}

	/**
	 * Returns true if there is a problem with the implementation of the database engine's 
	 * getCatalog() method, false if the getCatalog() method works fine.
	 * 
	 */
	public boolean getCatalogIsBroken()
	{
		//default is no, getCatalog has no problems.
		return false;
	}
	
	/**
	 * Returns true if the ResultSet.getObject method is broken in such a way that it
	 * returns subclasses of Number with the wrong width, e.g. Float instead of Double.
	 */
	public boolean getObjectIsBroken()
	{
		//default is no, getObject returns the right subclass of number
		return false;
	}
	
	/**
	 * Returns true if the PreparedStatement.setBoolean(...) method is broken in such 
	 * a way that we must use setInt(...) instead.
	 */
	public boolean setBooleanIsBroken()
	{
		//default is no, setBoolean is not broken
		return false;
	}

	/**
	 * Get a set of properties that are specific to the database engine.
	 * These properties will be passed to the database engine when a new Connection is initialised.
	 */
	public Properties getAdapterSpecificProperties()
	{
		//default is no properties 
		return new Properties();
	}
}
