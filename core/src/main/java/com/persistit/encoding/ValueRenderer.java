/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.persistit.encoding;

import com.persistit.Value;
import com.persistit.exception.ConversionException;

/**
 * <p>
 * An extended {@link ValueCoder} that can populate a supplied <tt>Object</tt>
 * with data that was formerly written to a {@link Value} by the
 * <tt>ValueCoder</tt>.
 * </p>
 * <p>
 * A <tt>ValueRenderer</tt> implements an additional method called
 * {@link #render} that populates a supplied target object rather than creating
 * a new object instance. The application provides the mutable target object to
 * {@link Value#get(Object)}.
 * </p>
 * <p>
 * A <tt>ValueRenderer</tt> can implement an application-level contract to
 * recover a limited set of fields from an encoded <tt>Value</tt> or to populate
 * a generic target object that might be suitable for utility display purposes.
 * For example, suppose there is a complex <tt>Person</tt> class with many
 * fields that refer to other objects. Deserializing and populating all the
 * fields of such a <tt>Person</tt> object might not be appropriate in an area
 * of the application that merely needs to display a list of names. To avoid
 * completely deserializing numerous <tt>Person</tt> objects, the application
 * could register a <tt>ValueRenderer</tt> implementation that knows how to
 * decode a serialized <tt>Person</tt> into a <tt>PersonListData</tt> object. To
 * do so it would {@link Value#get} the desired interior fields of the encoded
 * <tt>Person</tt> and {@link Value#skip} the other fields.
 * </p>
 * 
 * 
 * @version 1.0
 */
public interface ValueRenderer extends ValueCoder {
    /**
     * <p>
     * Populates the state of the supplied (mutable) target <tt>Object</tt> by
     * decoding the supplied <tt>Value</tt>. This method will be called only if
     * this <tt>ValueRenderer</tt> has been registered with the current
     * {@link CoderManager} to encode objects having the supplied <tt>Class</tt>
     * value. Persistit will never call this method to decode a value that was
     * <tt>null</tt> when written because null values are handled by built-in
     * encoding logic.
     * </p>
     * 
     * @param value
     *            The <tt>Value</tt> from which interior fields of the object
     *            are to be retrieved
     * 
     * @param target
     *            The object into which the decoded value is to be written
     * 
     * @param clazz
     *            The class of the object that was originally encoded into
     *            Value.
     * 
     * @param context
     *            An arbitrary object that can optionally be supplied by the
     *            application to convey an application-specific context for the
     *            operation. (See {@link CoderContext}.) The default value is
     *            <tt>null</tt>.
     * 
     * @throws ConversionException
     */
    public void render(Value value, Object target, Class clazz,
            CoderContext context) throws ConversionException;
}