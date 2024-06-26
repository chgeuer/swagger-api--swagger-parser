package io.swagger.parser.processors;

import io.swagger.models.*;
import io.swagger.models.properties.ArrayProperty;
import io.swagger.models.properties.ComposedProperty;
import io.swagger.models.properties.MapProperty;
import io.swagger.models.properties.ObjectProperty;
import io.swagger.models.properties.Property;
import io.swagger.models.properties.RefProperty;
import io.swagger.models.properties.StringProperty;
import io.swagger.models.refs.RefFormat;
import io.swagger.models.refs.RefType;
import io.swagger.parser.ResolverCache;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


import static io.swagger.parser.util.RefUtils.computeDefinitionName;
import static io.swagger.parser.util.RefUtils.isAnExternalRefFormat;

public final class ExternalRefProcessor {
    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(ExternalRefProcessor.class);

    private final ResolverCache cache;
    private final Swagger swagger;

    public ExternalRefProcessor(ResolverCache cache, Swagger swagger) {
        this.cache = cache;
        this.swagger = swagger;
    }

    public String processRefToExternalDefinition(String $ref, RefFormat refFormat) {
        String renamedRef = cache.getRenamedRef($ref);

        if(renamedRef != null) {
            return renamedRef;
        }

        final Model model = cache.loadRef($ref, refFormat, Model.class);

        if(model == null) {
            // stop!  There's a problem.  retain the original ref
            LOGGER.warn("unable to load model reference from `" + $ref + "`.  It may not be available " +
                    "or the reference isn't a valid model schema");
            return $ref;
        }
        String newRef;

        Map<String, Model> definitions = swagger.getDefinitions();

        if (definitions == null) {
            definitions = new LinkedHashMap<>();
        }

        final String possiblyConflictingDefinitionName = computeDefinitionName($ref);

        String tryName = null;
        Model existingModel = definitions.get(possiblyConflictingDefinitionName);

        if (existingModel != null) {
            LOGGER.debug("A model for " + existingModel + " already exists");
            if(existingModel instanceof RefModel) {
                // use the new model
                existingModel = null;
            }else{
                //We add a number at the end of the definition name
                int i = 2;
                for (String name : definitions.keySet()) {
                    if (name.equals(possiblyConflictingDefinitionName)) {
                        tryName = possiblyConflictingDefinitionName + "_" + i;
                        existingModel = definitions.get(tryName);
                        i++;
                    }
                }
            }
        }
        if (StringUtils.isNotBlank(tryName)){
            newRef = tryName;
        }else{
            newRef = possiblyConflictingDefinitionName;
        }
        cache.putRenamedRef($ref, newRef);
        if(existingModel == null) {
            // don't overwrite existing model reference
            swagger.addDefinition(newRef, model);
            cache.addReferencedKey(newRef);

            String file = $ref.split("#/")[0];
            if (model instanceof RefModel) {
                RefModel refModel = (RefModel) model;
                if (isAnExternalRefFormat(refModel.getRefFormat())) {
                    refModel.set$ref(processRefToExternalDefinition(refModel.get$ref(), refModel.getRefFormat()));
                } else {
                    refModel.set$ref(processRefToExternalDefinition(file + refModel.get$ref(), RefFormat.RELATIVE));
                }

            }
            if (model instanceof ComposedModel){
                
                ComposedModel composedModel = (ComposedModel) model;
                List<Model> listOfAllOF = composedModel.getAllOf();

                for (Model allOfModel: listOfAllOF){
                    if (allOfModel instanceof RefModel) {
                        RefModel refModel = (RefModel) allOfModel;
                        if (isAnExternalRefFormat(refModel.getRefFormat())) {
                            String joinedRef = join(file, refModel.get$ref());
                            refModel.set$ref(processRefToExternalDefinition(joinedRef, refModel.getRefFormat()));
                        }else {
                            processRefToExternalDefinition(file + refModel.get$ref(), RefFormat.RELATIVE);
                        }
                    } else if (allOfModel instanceof ModelImpl) {
                        //Loop through additional properties of allOf and recursively call this method;
                        processProperties(allOfModel.getProperties(), file);
                    }
                }
            }
            //Loop the properties and recursively call this method;
            processProperties(model.getProperties(), file);

            if (model instanceof  ModelImpl) {
                ModelImpl modelImpl = (ModelImpl) model;

                String discriminator = modelImpl.getDiscriminator();
                if (discriminator != null){
                    processDiscriminator(discriminator,modelImpl.getProperties(), file);
                }

                processProperties(Arrays.asList(modelImpl.getAdditionalProperties()), file);
            }
            if (model instanceof ArrayModel && ((ArrayModel) model).getItems() instanceof RefProperty) {
                processRefProperty((RefProperty) ((ArrayModel) model).getItems(), file);
            }

            if (model instanceof ArrayModel && ((ArrayModel) model).getItems() != null) {
                ArrayModel arraySchema = (ArrayModel) model;
                if (arraySchema.getItems() instanceof RefModel) {
                    processRefProperty((RefProperty) ((ArrayModel) model).getItems(), file);
                } else {
                    Property properties = arraySchema.getItems();
                    if (properties instanceof ObjectProperty) {
                        processProperties(((ObjectProperty) properties).getProperties(), file);
                    }
                }
            }
        }

        return newRef;
    }




    public String processRefToExternalResponse(String $ref, RefFormat refFormat) {

        String renamedRef = cache.getRenamedRef($ref);
        if(renamedRef != null) {
            return renamedRef;
        }
        final Response response = cache.loadRef($ref, refFormat, Response.class);

        String newRef;


        Map<String, Response> responses = swagger.getResponses();

        if (responses == null) {
            responses = new LinkedHashMap<>();
        }

        final String possiblyConflictingDefinitionName = computeDefinitionName($ref);

        Response existingResponse = responses.get(possiblyConflictingDefinitionName);

        if (existingResponse != null) {
            LOGGER.debug("A model for " + existingResponse + " already exists");
            if(existingResponse instanceof RefResponse) {
                // use the new model
                existingResponse = null;
            }
        }
        newRef = possiblyConflictingDefinitionName;
        cache.putRenamedRef($ref, newRef);

        if(response != null) {

            Model model = null;
            if (response.getResponseSchema() != null) {
                processRefSchemaObject(response.getResponseSchema(), $ref);
            }
        }
        return newRef;
    }

    private void processRefSchemaObject(Model schema, String $ref) {
        String file = $ref.split("#/")[0];

        if (schema instanceof RefModel) {
            RefModel refModel = (RefModel) schema;
            RefFormat ref = refModel.getRefFormat();
            if (isAnExternalRefFormat(ref)) {
                processRefModel(refModel, $ref);
            } else {
                processRefToExternalDefinition(file + refModel.get$ref(), RefFormat.RELATIVE);
            }
        }else{
            processSchema(schema,file);
        }
    }

    private void processSchema(Model property, String file) {
        if (property != null) {
            if (property instanceof RefModel) {
                processRefModel((RefModel)property, file);
            }
            if (property.getProperties() != null) {
                processProperties(property.getProperties(), file);
            }
            if (property instanceof ArrayModel) {
                processProperty(((ArrayModel) property).getItems(), file);
            }
            if (property instanceof MapProperty){
                MapProperty mapProperty = (MapProperty) property;
                if (mapProperty.getAdditionalProperties() instanceof Model) {
                    processProperty(mapProperty.getAdditionalProperties(), file);
                }
            }
            if (property instanceof ComposedModel) {
                ComposedModel composed = (ComposedModel) property;
                processComposedProperties(composed.getAllOf(), file);
            }
        }
    }

    private void processProperty(Property property, String file) {
    }

    private void processComposedProperties(Collection<Model> properties, String file) {
        if (properties != null) {
            for (Model property : properties) {
                processSchema(property, file);
            }
        }
    }


    private void processDiscriminator(String discriminator, Map<String, Property> properties, String file) {
    	if (properties == null || properties.isEmpty()) {
    		return;
    	}
        for (Map.Entry<String, Property> prop : properties.entrySet()) {
            if (prop.getKey().equals(discriminator)){
                if (prop.getValue() instanceof StringProperty){
                    StringProperty stringProperty = (StringProperty) prop.getValue();
                    if(stringProperty.getEnum() != null){
                        for(String name: stringProperty.getEnum()){
                            processRefProperty(new RefProperty(RefType.DEFINITION.getInternalPrefix()+name), file);
                        }
                    }
                }else if (prop.getValue() instanceof RefProperty) {
                    String ref = ((RefProperty) prop.getValue()).getSimpleRef();
                    Map<String, String> renameCache = cache.getRenameCache();
                    for (String key : renameCache.keySet()) {
                        String value = renameCache.get(key);
                        if (value.equals(ref)) {
                            Object resolved = cache.getResolutionCache().get(key);
                            if(resolved != null) {
                                if (resolved instanceof ModelImpl) {
                                    ModelImpl schema = (ModelImpl) resolved;
                                    if (schema.getEnum() != null) {
                                        for (String name : schema.getEnum()) {
                                            processRefProperty(new RefProperty(RefType.DEFINITION.getInternalPrefix() + name), file);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    }

    private void processProperties(final Map<String, Property> subProps, final String file) {
        if (subProps == null || subProps.isEmpty()) {
            return;
        }
        processProperties(subProps.values(), file);
    }

    private void processProperties(final Collection<Property> subProps, final String file) {
        if (subProps == null || subProps.isEmpty()) {
            return;
        }
        for (Property prop : subProps) {
            if (prop instanceof RefProperty) {
                processRefProperty((RefProperty) prop, file);
            } else if (prop instanceof ArrayProperty) {
                processProperties(Arrays.asList(((ArrayProperty) prop).getItems()), file);
            } else if (prop instanceof MapProperty) {
                processProperties(Arrays.asList(((MapProperty) prop).getAdditionalProperties()), file);
            } else if (prop instanceof ObjectProperty) {
                processProperties(((ObjectProperty) prop).getProperties(), file);
            } else if (prop instanceof ComposedProperty) {
                processProperties(((ComposedProperty) prop).getAllOf(), file);
            }
        }
    }

    private void processDiscriminatorAsRefProperty(RefProperty subRef, String externalFile) {

        if (isAnExternalRefFormat(subRef.getRefFormat())) {
            String joinedRef = join(externalFile, subRef.get$ref());
            subRef.set$ref(processRefToExternalDefinition(joinedRef, subRef.getRefFormat()));
        } else {
            String processRef = processRefToExternalDefinition(externalFile + subRef.get$ref(), RefFormat.RELATIVE);
            subRef.set$ref(RefType.DEFINITION.getInternalPrefix()+processRef);
        }
    }

    private void processRefProperty(RefProperty subRef, String externalFile) {

        if (isAnExternalRefFormat(subRef.getRefFormat())) {

            String joinedRef = join(externalFile, subRef.get$ref());
            String processRef = processRefToExternalDefinition(joinedRef, subRef.getRefFormat());
            if(processRef.startsWith("http") || processRef.startsWith("https:")) {
                subRef.set$ref(processRef);
            }else {
                subRef.set$ref(RefType.DEFINITION.getInternalPrefix()+processRef);
            }
        } else {
            String processRef = processRefToExternalDefinition(externalFile + subRef.get$ref(), RefFormat.RELATIVE);
            subRef.set$ref(RefType.DEFINITION.getInternalPrefix()+processRef);
        }
    }

    private void processRefModel(RefModel subRef, String externalFile) {

        RefFormat format = subRef.getRefFormat();

        if (!isAnExternalRefFormat(format)) {
            subRef.set$ref(RefType.DEFINITION.getInternalPrefix()+ processRefToExternalDefinition(externalFile + subRef.get$ref(), RefFormat.RELATIVE));
            return;
        }
        String $ref = subRef.get$ref();
        String subRefExternalPath = getExternalPath(subRef.get$ref());

        if (format.equals(RefFormat.RELATIVE) && !Objects.equals(subRefExternalPath, externalFile)) {
            $ref = constructRef(subRef, externalFile);
            subRef.set$ref($ref);
        }else {
            processRefToExternalDefinition($ref, format);
        }
    }

    protected String constructRef(Model refProperty, String rootLocation) {
        RefModel refModel = (RefModel) refProperty;
        String ref = refModel.get$ref();
        return join(rootLocation, ref);
    }

    public static String getExternalPath(String ref) {
        if (ref == null) {
            return null;
        }
        String[] elements = ref.split("#/");
        String element = null;
        for (int i = 0; i < elements.length; i++ ) {
            if (elements[i].length() == 2){
                 element  = elements[i];
            }
        }
        return element;
    }

    public static String join(String source, String fragment) {
        try {
            boolean isRelative = false;
            if(source.startsWith("/") || source.startsWith(".")) {
                isRelative = true;
            }
            URI uri = new URI(source);

            if(!source.endsWith("/") && (fragment.startsWith("./") && "".equals(uri.getPath()))) {
                uri = new URI(source + "/");
            }
            else if("".equals(uri.getPath()) && !fragment.startsWith("/")) {
                uri = new URI(source + "/");
            }
            URI f = new URI(fragment);

            URI resolved = uri.resolve(f);

            URI normalized = resolved.normalize();

            if(Character.isAlphabetic(normalized.toString().charAt(0)) && isRelative) {
                return "./" + normalized.toString();
            }
            return normalized.toString();
        }
        catch(Exception e) {
            return source;
        }
    }
}
