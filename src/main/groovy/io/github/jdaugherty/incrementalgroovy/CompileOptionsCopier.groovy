/*
 * Copyright the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.jdaugherty.incrementalgroovy

import groovy.transform.CompileStatic
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.HasMultipleValues
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Copies the state of the compile options of a {@code GroovyCompile} task to the options of the task that compiles for it.
 * <p>
 * The two tasks cannot share the options themselves, as options belong to their task. The copy goes by the public
 * properties of the option types, so that it follows them as Gradle adds to them.
 */
@CompileStatic
final class CompileOptionsCopier {
    private static final String OPTIONS_PACKAGE = 'org.gradle.api.tasks.compile'

    private final ProviderFactory providers

    CompileOptionsCopier(ProviderFactory providers) {
        this.providers = providers
    }

    /**
     * @param type the public type of the options
     */
    void copy(Class<?> type, Object from, Object to) {
        for (Method getter : type.methods) {
            if (!isOptionGetter(getter)) {
                continue
            }
            Object value = getter.invoke(from)
            Class<?> valueType = getter.returnType
            if (Property.isAssignableFrom(valueType)) {
                Property<Object> source = (Property<Object>) value
                ((Property<Object>) getter.invoke(to)).set(providers.provider { source.getOrNull() })
            } else if (HasMultipleValues.isAssignableFrom(valueType)) {
                HasMultipleValues<Object> source = (HasMultipleValues<Object>) value
                ((HasMultipleValues<Object>) getter.invoke(to)).set(providers.provider { (Iterable<Object>) ((Provider<Object>) (Object) source).getOrNull() })
            } else if (MapProperty.isAssignableFrom(valueType)) {
                MapProperty<Object, Object> source = (MapProperty<Object, Object>) value
                ((MapProperty<Object, Object>) getter.invoke(to)).set(providers.provider { source.getOrNull() })
            } else if (ConfigurableFileCollection.isAssignableFrom(valueType)) {
                ((ConfigurableFileCollection) getter.invoke(to)).setFrom(value)
            } else if (valueType.package?.name == OPTIONS_PACKAGE) {
                // nested options, such as the fork options
                copy(valueType, value, getter.invoke(to))
            } else {
                Method setter = setterFor(type, getter)
                if (setter != null) {
                    setter.invoke(to, copyOf(value))
                } else if (value instanceof List && !getter.name.startsWith('getAll')) {
                    // a list to add to, such as the argument providers
                    List<Object> target = (List<Object>) getter.invoke(to)
                    target.clear()
                    target.addAll((List<Object>) value)
                }
            }
        }
    }

    private static boolean isOptionGetter(Method method) {
        if (method.parameterCount != 0 || Modifier.isStatic(method.modifiers) || method.declaringClass.package?.name != OPTIONS_PACKAGE) {
            return false
        }
        return (method.name.startsWith('get') && method.name.length() > 3) || (method.name.startsWith('is') && method.returnType == boolean)
    }

    private static Method setterFor(Class<?> type, Method getter) {
        String name = 'set' + getter.name.substring(getter.name.startsWith('is') ? 2 : 3)
        return type.methods.find { Method method ->
            method.name == name && method.parameterCount == 1 && method.parameterTypes[0].isAssignableFrom(getter.returnType)
        }
    }

    private static Object copyOf(Object value) {
        if (value instanceof List) {
            return new ArrayList<Object>((List<Object>) value)
        }
        if (value instanceof Map) {
            return new LinkedHashMap<Object, Object>((Map<Object, Object>) value)
        }
        return value
    }
}
