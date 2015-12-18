/*
 * Copyright 2015 the original author or authors.
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

package org.gradle.model.internal.manage.schema;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import org.gradle.model.internal.manage.schema.extract.ModelSchemaAspect;
import org.gradle.model.internal.method.WeaklyTypeReferencingMethod;
import org.gradle.model.internal.type.ModelType;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

import static org.gradle.model.internal.manage.schema.extract.ModelSchemaUtils.weakMethodOrder;

public class NewStructSchema<T> extends AbstractModelSchema<T> implements CompositeSchema<T> {
    private final Map<String, NewModelProperty<?>> properties;
    private final Set<WeaklyTypeReferencingMethod<?, ?>> nonPropertyMethods;
    private final Set<? extends ModelSchemaAspect> aspects;

    public NewStructSchema(ModelType<T> type, Map<String, NewModelProperty<?>> properties, Iterable<WeaklyTypeReferencingMethod<?, ?>> nonPropertyMethods, Iterable<? extends ModelSchemaAspect> aspects) {
        super(type);
        this.properties = ImmutableSortedMap.copyOf(properties);
        this.nonPropertyMethods = ImmutableSortedSet.copyOf(weakMethodOrder(), nonPropertyMethods);
        this.aspects = ImmutableSet.copyOf(aspects);
    }

    public Collection<NewModelProperty<?>> getProperties() {
        return properties.values();
    }

    public boolean hasProperty(String name) {
        return properties.containsKey(name);
    }

    public Set<WeaklyTypeReferencingMethod<?, ?>> getNonPropertyMethods() {
        return nonPropertyMethods;
    }

    public Set<WeaklyTypeReferencingMethod<?, ?>> getAllMethods() {
        ImmutableSortedSet.Builder<WeaklyTypeReferencingMethod<?, ?>> builder = ImmutableSortedSet.orderedBy(weakMethodOrder());
        for (NewModelProperty<?> property : properties.values()) {
            builder.addAll(property.getAccessorMethods());
        }
        builder.addAll(nonPropertyMethods);
        return builder.build();
    }

    public Set<? extends ModelSchemaAspect> getAspects() {
        return aspects;
    }
}
