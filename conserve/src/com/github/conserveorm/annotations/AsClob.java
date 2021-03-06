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
package com.github.conserveorm.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This annotation indicates that the return value of the annotated method should be stored as a CLOB (Character Large
 * OBject), rather than as an array.
 * 
 * Clobs are effectively primitives, as they are treated as immutable by Conserve: If two objects A and B share a
 * reference to a CLOB and the object A changes its CLOB, the CLOB of object B remains unchanged. This is the opposite
 * of what would happen if the object was stored as an array, since arrays are mutable, but similar to how the object
 * would be treated if it was stored as a String, since strings are immutable.
 * 
 * This annotation only has meaning if it is applied to accessors which return values of type char[]. In all other cases it will be ignored.
 * This annotation will be silently ignored if the underlying database management system does not support anything equivalent to the CLOB datatype.
 * 
 * @author Erik Berglund
 * 
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AsClob
{

}
