/*
 * Copyright (c) 2014, Danilo Reinert (daniloreinert@growbit.com)
 *
 * This software is dual-licensed under:
 *
 * - the Lesser General Public License (LGPL) version 3.0 or, at your option, any
 *   later version;
 * - the Apache Software License (ASL) version 2.0.
 *
 * The text of both licenses is available under the src/resources/ directory of
 * this project (under the names LGPL-3.0.txt and ASL-2.0.txt respectively).
 *
 * Direct link to the sources:
 *
 * - LGPL 3.0: https://www.gnu.org/licenses/lgpl-3.0.txt
 * - ASL 2.0: http://www.apache.org/licenses/LICENSE-2.0.txt
 */

package com.github.reinert.jjschema;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.math.BigDecimal;
import java.util.*;
import java.util.Map.Entry;

/**
 * Generates JSON schema from Java Types
 *
 * @author reinert
 */
public abstract class JsonSchemaGenerator {

    final ObjectMapper mapper = new ObjectMapper();
    boolean autoPutVersion = true;
    private Set<ManagedReference> forwardReferences;
    private Set<ManagedReference> backReferences;

    Set<ManagedReference> getForwardReferences() {
        if (forwardReferences == null)
            forwardReferences = new LinkedHashSet<ManagedReference>();
        return forwardReferences;
    }

    <T> void pushFowardReference(ManagedReference fowardReference) {
        getForwardReferences().add(fowardReference);
    }

    <T> boolean isFowardReferencePiled(ManagedReference fowardReference) {
        return getForwardReferences().contains(fowardReference);
    }

    <T> boolean pullFowardReference(ManagedReference fowardReference) {
        return getForwardReferences().remove(fowardReference);
    }

    Set<ManagedReference> getBackwardReferences() {
        if (backReferences == null)
            backReferences = new LinkedHashSet<ManagedReference>();
        return backReferences;
    }

    <T> void pushBackwardReference(ManagedReference backReference) {
        getBackwardReferences().add(backReference);
    }

    <T> boolean isBackwardReferencePiled(ManagedReference backReference) {
        return getBackwardReferences().contains(backReference);
    }

    <T> boolean pullBackwardReference(ManagedReference backReference) {
        return getBackwardReferences().remove(backReference);
    }

//    void resetProcessedReferences() {
//    	processedReferences = null;
//    }
//    
//    /**
//     * Reset all utility fields used for generating schemas when asked by the user
//     */
//    void reset() {
//    	resetProcessedReferences();
//    }

    protected ObjectNode createRefSchema(String ref) {
        return createInstance().put("$ref", ref);
    }

    protected JsonSchemaGenerator() {
    }

    /**
     * Reads {@link Attributes} annotation and put its values into the
     * generating schema. Usually, some verification is done for not putting the
     * default values.
     *
     * @param schema
     * @param props
     */
    abstract protected void processSchemaProperty(ObjectNode schema, Attributes props);

    protected ObjectNode createInstance() {
        return mapper.createObjectNode();
    }

    /**
     * Checks if this generator should put the $schema attribute at the root
     * schema.
     *
     * @return true if it should put the $schema attribute, false otherwise
     */
    public boolean isAutoPutVersion() {
        return autoPutVersion;
    }

    /**
     * If true, this parameter says that the $schema atribute should be put at
     * the root of all schemas generated by this SchemaGenerator instace.
     *
     * @param autoPutVersion
     * @return the actual instance of JsonSchemaGenerator
     */
    public JsonSchemaGenerator setAutoPutVersion(boolean autoPutVersion) {
        this.autoPutVersion = autoPutVersion;
        return this;
    }

    public <T> ObjectNode generateSchema(Class<T> type) {
        ObjectNode schema = createInstance();
        schema = checkAndProcessType(type, schema);
        return schema;
    }

    /**
     * Checks whether the type is SimpleType (mapped by
     * {@link SimpleTypeMappings}), Collection or Iterable (for mapping arrays),
     * Void type (returning null), or custom Class (for mapping objects).
     *
     * @param type
     * @param schema
     * @return the full schema represented as an ObjectNode.
     */
    protected <T> ObjectNode checkAndProcessType(Class<T> type, ObjectNode schema) {
        String s = SimpleTypeMappings.forClass(type);
        // If it is a simple type, then just put the type
        if (s != null) {
            schema.put("type", s);
        }
        // If it is a Collection or Iterable the generate the schema as an array
        else if (Iterable.class.isAssignableFrom(type)
                || Collection.class.isAssignableFrom(type)) {
            checkAndProcessCollection(type, schema);
        }
        // If it is void then return null
        else if (type == Void.class || type == void.class) {
            schema = null;
        }
        // If it is an Enum than process like enum
        else if (type.isEnum()) {
            processEnum(type, schema);
        }
        // If none of the above possibilities were true, then it is a custom object
        else {
            schema = processCustomType(type, schema);
        }
        return schema;
    }

    /**
     * Generates the schema of custom java types
     *
     * @param type
     * @param schema
     * @return the full schema of custom java types
     */
    protected <T> ObjectNode processCustomType(Class<T> type, ObjectNode schema) {
        schema.put("type", "object");
        // fill root object properties
        processRootAttributes(type, schema);
        // Generate the schemas of type's properties
        processProperties(type, schema);
        // Merge the actual type's schema with a parent type's schema (if it exists!)
        schema = mergeWithParent(type, schema);

        return schema;
    }

    /**
     * Generates the schema of collections java types
     *
     * @param type
     * @param schema
     */
    private <T> void checkAndProcessCollection(Class<T> type, ObjectNode schema) {
        // If the type extends from AbstracctCollection, then it is considered
        // as a simple array type
        if (AbstractCollection.class.isAssignableFrom(type)) {
            schema.put("type", "array");
        }
        // Otherwise it is processed as a custom array type
        else {
            processRootAttributes(type, schema);
            // NOTE: Customized Iterable/Collection Wrapper Class must declare
            // the intended Collection as the first field
            processCustomCollection(type, schema);
        }
    }

    private <T> void processCustomCollection(Class<T> type, ObjectNode schema) {
        schema.put("type", "array");
        Field field = type.getDeclaredFields()[0];
        ParameterizedType genericType = (ParameterizedType) field
                .getGenericType();
        Class<?> genericClass = (Class<?>) genericType.getActualTypeArguments()[0];
        ObjectNode itemsSchema = generateSchema(genericClass);
        itemsSchema.remove("$schema");
        schema.put("items", itemsSchema);
    }

    private <T> void processEnum(Class<T> type, ObjectNode schema) {
        ArrayNode enumArray = schema.putArray("enum");
        for (T constant : type.getEnumConstants()) {
            String value = constant.toString();
            // Check if value is numeric
            try {
                // First verifies if it is an integer
                Long integer = Long.parseLong(value);
                enumArray.add(integer);
            }
            // If not then verifies if it is an floating point number
            catch (NumberFormatException e) {
                try {
                    BigDecimal number = new BigDecimal(value);
                    enumArray.add(number);
                }
                // Otherwise add as String
                catch (NumberFormatException e1) {
                    enumArray.add(value);
                }
            }
        }
    }

    private void processPropertyCollection(Method method, ObjectNode schema) {
        schema.put("type", "array");
        ParameterizedType genericType = (ParameterizedType) method
                .getGenericReturnType();
        Class<?> genericClass = (Class<?>) genericType.getActualTypeArguments()[0];
        schema.put("items", generateSchema(genericClass));
    }

    protected <T> void processRootAttributes(Class<T> type, ObjectNode schema) {
        Attributes sProp = type.getAnnotation(Attributes.class);
        if (sProp != null)
            processSchemaProperty(schema, sProp);
    }

    protected <T> void processProperties(Class<T> type, ObjectNode schema) {
        HashMap<Method, Field> props = findProperties(type);
        for (Map.Entry<Method, Field> entry : props.entrySet()) {
            Field field = entry.getValue();
            Method method = entry.getKey();
            ObjectNode prop = generatePropertySchema(type, method, field);
            if (prop != null) {
                addPropertyToSchema(schema, field, method, prop);
            }
        }
    }

    protected <T> ObjectNode generatePropertySchema(Class<T> type, Method method, Field field) {
        Class<?> returnType = method.getReturnType();
        AccessibleObject propertyReflection =
                (AccessibleObject) field != null ? field : method;

        SchemaIgnore ignoreAnn = propertyReflection.getAnnotation(SchemaIgnore.class);
        if (ignoreAnn != null)
            return null;

        ObjectNode schema = createInstance();

        JsonManagedReference refAnn = propertyReflection.getAnnotation(JsonManagedReference.class);
        if (refAnn != null) {
            ManagedReference fowardReference;
            Class<?> genericClass;
            Class<?> collectionClass;
            if (Collection.class.isAssignableFrom(returnType)) {
                ParameterizedType genericType = (ParameterizedType) method
                        .getGenericReturnType();
                genericClass = (Class<?>) genericType.getActualTypeArguments()[0];
                collectionClass = returnType;
            } else {
                genericClass = returnType;
            }
            fowardReference = new ManagedReference(type, refAnn.value(), genericClass);

            if (!isFowardReferencePiled(fowardReference)) {
                pushFowardReference(fowardReference);
            } else
//        	if (isBackwardReferencePiled(fowardReference)) 
            {
                boolean a = pullFowardReference(fowardReference);
                boolean b = pullBackwardReference(fowardReference);
                //return null;
                return createRefSchema("#");
            }
        }

        JsonBackReference backRefAnn = propertyReflection.getAnnotation(JsonBackReference.class);
        if (backRefAnn != null) {
            ManagedReference backReference;
            Class<?> genericClass;
            Class<?> collectionClass;
            if (Collection.class.isAssignableFrom(returnType)) {
                ParameterizedType genericType = (ParameterizedType) method
                        .getGenericReturnType();
                genericClass = (Class<?>) genericType.getActualTypeArguments()[0];
                collectionClass = returnType;
            } else {
                genericClass = returnType;
            }
            backReference = new ManagedReference(genericClass, backRefAnn.value(), type);

            if (isFowardReferencePiled(backReference) &&
                    !isBackwardReferencePiled(backReference)) {
                pushBackwardReference(backReference);
            } else {
//        		pullFowardReference(backReference);
//        		pullBackwardReference(backReference);
                return null;
            }
        }


        if (Collection.class.isAssignableFrom(returnType)) {
            processPropertyCollection(method, schema);
        } else {
            schema = generateSchema(returnType);
        }

        // Check the field annotations, if the get method references a field, or the
        // method annotations on the other hand, and processSchemaProperty them to
        // the JsonSchema object
        Attributes attrs = propertyReflection.getAnnotation(Attributes.class);
        if (attrs != null) {
            processSchemaProperty(schema, attrs);
            // The declaration of $schema is only necessary at the root object
            schema.remove("$schema");
        }

        // Check if the Nullable annotation is present, and if so, add 'null' to type attr
        Nullable nullable = propertyReflection.getAnnotation(Nullable.class);
        if (nullable != null) {
            if (returnType.isEnum()) {
                ((ArrayNode) schema.get("enum")).add("null");
            } else {
                String oldType = schema.get("type").asText();
                ArrayNode typeArray = schema.putArray("type");
                typeArray.add(oldType);
                typeArray.add("null");
            }
        }

        return schema;
    }

    private void addPropertyToSchema(ObjectNode schema, Field field,
                                     Method method, ObjectNode prop) {
        String name = getPropertyName(field, method);
        if (prop.has("selfRequired")) {
            ArrayNode requiredNode;
            if (!schema.has("required")) {
                requiredNode = schema.putArray("required");
            } else {
                requiredNode = (ArrayNode) schema.get("required");
            }
            requiredNode.add(name);
            prop.remove("selfRequired");
        }
        if (!schema.has("properties"))
            schema.putObject("properties");
        ((ObjectNode) schema.get("properties")).put(name, prop);
    }

    private String getPropertyName(Field field, Method method) {
        String name = (field == null) ? firstToLowCase(method.getName()
                .replace("get", "")) : field.getName();
        return name;
    }

    /**
     * If the Java Type inherits from other Java Type but Object, then it is
     * assumed to inherit from other custom type. In this case, the parent class
     * is processed as well and merged with the child class, having the child a
     * high priority when both have same attributes filled.
     *
     * @param type
     * @param schema
     * @return The actual schema merged with its parent schema (if it exists)
     */
    protected <T> ObjectNode mergeWithParent(Class<T> type, ObjectNode schema) {
        Class<? super T> superclass = type.getSuperclass();
        if (superclass != null && superclass != Object.class) {
            ObjectNode parentSchema = generateSchema(superclass);
            schema = mergeSchema(parentSchema, schema, false);
        }
        return schema;
    }

    /**
     * Merges two schemas.
     *
     * @param parent                   A parent schema considering inheritance
     * @param child                    A child schema considering inheritance
     * @param overwriteChildProperties A boolean to check whether properties (from parent or child) must have higher priority
     * @return The tow schemas merged
     */
    protected ObjectNode mergeSchema(ObjectNode parent, ObjectNode child,
                                     boolean overwriteChildProperties) {
        Iterator<String> namesIterator = child.fieldNames();

        if (overwriteChildProperties) {
            while (namesIterator.hasNext()) {
                String propertyName = namesIterator.next();
                overwriteProperty(parent, child, propertyName);
            }

        } else {

            while (namesIterator.hasNext()) {
                String propertyName = namesIterator.next();
                if (!propertyName.equals("properties")) {
                    overwriteProperty(parent, child, propertyName);
                }
            }

            ObjectNode properties = (ObjectNode) child.get("properties");
            if (properties != null) {
                if (parent.get("properties") == null) {
                    parent.putObject("properties");
                }

                Iterator<Entry<String, JsonNode>> it = properties.fields();
                while (it.hasNext()) {
                    Entry<String, JsonNode> entry = it.next();
                    String pName = entry.getKey();
                    ObjectNode pSchema = (ObjectNode) entry.getValue();
                    ObjectNode actualSchema = (ObjectNode) parent.get(
                            "properties").get(pName);
                    if (actualSchema != null) {
                        mergeSchema(pSchema, actualSchema, false);
                    }
                    ((ObjectNode) parent.get("properties")).put(pName, pSchema);
                }
            }
        }

        return parent;
    }

    protected void overwriteProperty(ObjectNode parent, ObjectNode child,
                                     String propertyName) {
        if (child.has(propertyName)) {
            parent.put(propertyName, child.get(propertyName));
        }
    }

    /**
     * Utility method to find properties from a Java Type following Beans Convention.
     *
     * @param type
     * @return
     */
    private <T> HashMap<Method, Field> findProperties(Class<T> type) {
        Field[] fields = type.getDeclaredFields();
        Method[] methods = type.getMethods();
        // Ordering the properties
        Arrays.sort(methods, new Comparator<Method>() {
            public int compare(Method m1, Method m2) {
                return m1.getName().compareTo(m2.getName());
            }
        });

        LinkedHashMap<Method, Field> props = new LinkedHashMap<Method, Field>();
        // get valid properties (get method and respective field (if exists))
        for (Method method : methods) {
            Class<?> declaringClass = method.getDeclaringClass();
            if (declaringClass.equals(Object.class)
                    || Collection.class.isAssignableFrom(declaringClass)) {
                continue;
            }

            if (isGetter(method)) {
                boolean hasField = false;
                for (Field field : fields) {
                    String name = getNameFromGetter(method);
                    if (field.getName().equalsIgnoreCase(name)) {
                        props.put(method, field);
                        hasField = true;
                        break;
                    }
                }
                if (!hasField) {
                    props.put(method, null);
                }
            }
        }
        return props;
    }

    private String firstToLowCase(String string) {
        return Character.toLowerCase(string.charAt(0))
                + (string.length() > 1 ? string.substring(1) : "");
    }

    private boolean isGetter(final Method method) {
        return method.getName().startsWith("get") || method.getName().startsWith("is");
    }

    private String getNameFromGetter(final Method getter) {
        String[] getterPrefixes = {"get", "is"};
        String methodName = getter.getName();
        String fieldName = null;
        for (String prefix : getterPrefixes) {
            if (methodName.startsWith(prefix)) {
                fieldName = methodName.substring(prefix.length());
            }
        }

        if (fieldName == null) {
            return null;
        }

        fieldName = fieldName.substring(0, 1).toLowerCase() + fieldName.substring(1);
        return fieldName;
    }
}
