/*   Copyright (C) 2013-2014 Computer Sciences Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. */

package ezbake.data.graph.blueprints.schema;

/**
 * Thrown when schema constraints are violated in some way.
 */
public class SchemaViolationException extends IllegalArgumentException {

    /**
     * Construct a new SchemaViolationException where the given message is passed to the parent constructor.
     *
     * @param message exception message
     */
    public SchemaViolationException(String message) {
        super(message);
    }

    /**
     * Construct a new SchemaViolationException where the given message and exception are passed to the parent
     * constructor.
     *
     * @param message exception message
     * @param e cause exception
     */
    public SchemaViolationException(String message, Throwable e) {
        super(message, e);
    }
}
