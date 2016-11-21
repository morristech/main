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

/**
 * @author Erik Berglund
 *
 */
public class MariaDBPersistTest extends PersistTest
{
	@Override
	protected void setupConnectionConstants()
	{
		driver = "org.mariadb.jdbc.Driver";
		database = "jdbc:mariadb://localhost/test";
		secondDatabase = "jdbc:mariadb://localhost/test2";
		login = "test";
		password = "test";
	}
}
