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
package com.github.conserveorm;

/**
 * Runs the integration test script on the H2 database engine.
 * @author Erik Berglund
 *
 */
public class H2Test extends PersistTest
{

	@Override
	protected void setupConnectionConstants()
	{
		driver = "org.h2.Driver";
		
		//embedded:
		database = "jdbc:h2:~/test";
		secondDatabase = "jdbc:h2:~/test2";
		
		//server:
		//database = "jdbc:h2:tcp://localhost/~/test";
		//secondDatabase = "jdbc:h2:tcp://localhost/~/test2";
		
		login = "sa";
		password = "";
	}

}
