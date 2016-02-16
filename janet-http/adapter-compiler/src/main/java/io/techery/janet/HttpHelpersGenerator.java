package io.techery.janet;


import com.google.common.reflect.TypeToken;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import org.apache.commons.lang.StringUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import javax.annotation.processing.Filer;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;

import io.techery.janet.body.ActionBody;
import io.techery.janet.body.BytesArrayBody;
import io.techery.janet.compiler.utils.Generator;
import io.techery.janet.converter.Converter;
import io.techery.janet.http.annotations.Body;
import io.techery.janet.http.annotations.Field;
import io.techery.janet.http.annotations.HttpAction;
import io.techery.janet.http.annotations.Part;
import io.techery.janet.http.annotations.Path;
import io.techery.janet.http.annotations.Query;
import io.techery.janet.http.annotations.RequestHeader;
import io.techery.janet.http.annotations.ResponseHeader;
import io.techery.janet.http.annotations.Status;
import io.techery.janet.body.FileBody;
import io.techery.janet.http.model.Header;
import io.techery.janet.http.model.Response;

import io.techery.janet.compiler.utils.TypeUtils;
import static io.techery.janet.compiler.utils.TypeUtils.equalTypes;


public class HttpHelpersGenerator extends Generator<HttpActionClass> {
    static final String HELPER_SUFFIX = "Helper";
    private static final String BASE_HEADERS_MAP = "headers";

    public HttpHelpersGenerator(Filer filer) {
        super(filer);
    }

    @Override
    public void generate(ArrayList<HttpActionClass> actionClasses) {
        for (HttpActionClass httpActionClass : actionClasses) {
            generate(httpActionClass);
        }
    }

    private void generate(HttpActionClass actionClass) {
        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(actionClass.getHelperName())
                .addModifiers(Modifier.PUBLIC)
                .addJavadoc("Janet compile time, autogenerated class, which fills actions")
                .addSuperinterface(ParameterizedTypeName.get(ClassName.get(HttpActionAdapter.ActionHelper.class), actionClass.getTypeName()));

        classBuilder.addMethod(createFillRequestMethod(actionClass));
        classBuilder.addMethod(createOnResponseMethod(actionClass));
        saveClass(actionClass.getPackageName(), classBuilder.build());
    }

    private MethodSpec createFillRequestMethod(HttpActionClass actionClass) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("fillRequest")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .returns(RequestBuilder.class)
                .addParameter(RequestBuilder.class, "requestBuilder")
                .addParameter(TypeName.get(actionClass.getTypeElement().asType()), "action")
                .addStatement("requestBuilder.setMethod($T.$L)", HttpAction.Method.class, actionClass.getMethod())
                .addStatement("requestBuilder.setRequestType($T.$L)", HttpAction.Type.class, actionClass.getRequestType())
                .addStatement("requestBuilder.setPath($S)", actionClass.getPath());
        addPathParams(actionClass, builder);
        addParts(actionClass, builder);
        addRequestHeaders(actionClass, builder);
        addRequestFields(actionClass, builder);
        addRequestQueries(actionClass, builder);
        addRequestBody(actionClass, builder);
        builder.addStatement("return requestBuilder");
        return builder.build();
    }

    private void addRequestFields(HttpActionClass actionClass, MethodSpec.Builder builder) {
        for (Element element : actionClass.getAnnotatedElements(Field.class)) {
            Field annotation = element.getAnnotation(Field.class);
            builder.beginControlFlow("if (action.$L != null)", element);
            builder.addStatement("requestBuilder.addField($S, action.$L.toString())", annotation.value(), element);
            builder.endControlFlow();
        }
    }

    private void addRequestQueries(HttpActionClass actionClass, MethodSpec.Builder builder) {
        for (Element element : actionClass.getAnnotatedElements(Query.class)) {
            Query annotation = element.getAnnotation(Query.class);
            if (TypeUtils.isPrimitive(element)) {
                builder.addStatement("requestBuilder.addQueryParam($S, $T.valueOf(action.$L), $L, $L)", annotation.value(), String.class, element, annotation.encodeName(), annotation.encodeValue());
            } else {
                builder.beginControlFlow("if (action.$L != null)", element);
                builder.addStatement("requestBuilder.addQueryParam($S, action.$L.toString(), $L, $L)", annotation.value(), element, annotation.encodeName(), annotation.encodeValue());
                builder.endControlFlow();
            }
        }
    }

    private void addRequestBody(HttpActionClass actionClass, MethodSpec.Builder builder) {
        for (Element element : actionClass.getAnnotatedElements(Body.class)) {
            builder.addStatement("requestBuilder.setBody(action.$L)", element);
            break;
        }
    }

    private void addRequestHeaders(HttpActionClass actionClass, MethodSpec.Builder builder) {
        for (Element element : actionClass.getAnnotatedElements(RequestHeader.class)) {
            RequestHeader annotation = element.getAnnotation(RequestHeader.class);
            builder.beginControlFlow("if (action.$L != null)", element);
            builder.addStatement("requestBuilder.addHeader($S, action.$L.toString())", annotation.value(), element);
            builder.endControlFlow();
        }
    }

    private void addPathParams(HttpActionClass actionClass, MethodSpec.Builder builder) {
        for (Element element : actionClass.getAnnotatedElements(Path.class)) {
            Path param = element.getAnnotation(Path.class);
            String path = param.value();
            String name = element.getSimpleName().toString();
            if (StringUtils.isEmpty(path)) {
                path = name;
            }
            boolean encode = param.encode();
            builder.beginControlFlow("if (action.$L != null)", name);
            builder.addStatement("requestBuilder.addPathParam($S, action.$L.toString(), $L)", path, name, encode);
            builder.endControlFlow();
        }
    }

    private void addParts(HttpActionClass actionClass, MethodSpec.Builder builder) {
        for (Element element : actionClass.getAnnotatedElements(Part.class)) {
            Part part = element.getAnnotation(Part.class);
            String partName = part.value();
            String name = element.getSimpleName().toString();
            if (StringUtils.isEmpty(partName)) {
                partName = name;
            }
            String encode = part.encoding();
            String httpBodyName = "httpBody";
            builder.beginControlFlow("if (action.$L != null)", name);
            if (TypeUtils.equalTypes(element, File.class)) {
                builder.addStatement("$T $L = new $T($S, action.$L)", ActionBody.class,httpBodyName, FileBody.class, encode, name);
            } else if (TypeUtils.equalTypes(element, byte[].class)) {
                builder.addStatement("$T $L = new $T($S, action.$L)", ActionBody.class,httpBodyName, BytesArrayBody.class, encode, name);
            } else if (TypeUtils.equalTypes(element, String.class)) {
                builder.addStatement("$T $L = new $T($S, action.$L.getBytes())",ActionBody.class, httpBodyName, BytesArrayBody.class, encode, name);
            } else {
                builder.addStatement("$T $L = action.$L", ActionBody.class,httpBodyName, name);
            }
            builder.addStatement("requestBuilder.addPart($S, $L, $S)", partName, httpBodyName, encode);
            builder.endControlFlow();
        }
    }

    private MethodSpec createOnResponseMethod(HttpActionClass actionClass) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("onResponse")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .returns(ClassName.get(actionClass.getTypeElement().asType()))
                .addParameter(actionClass.getTypeName(), "action")
                .addParameter(Response.class, "response")
                .addParameter(Converter.class, "converter");

        addStatusField(actionClass, builder);
        addResponses(actionClass, builder);
        addBasicHeadersMap(actionClass, builder);
        addResponseHeaders(actionClass, builder);
        builder.addStatement("return action");
        return builder.build();
    }

    private void addResponses(HttpActionClass actionClass, MethodSpec.Builder builder) {
        List<Element> responseElements = actionClass.getAnnotatedElements(io.techery.janet.http.annotations.Response.class);

        for (Element element : responseElements) {
            int status = element.getAnnotation(io.techery.janet.http.annotations.Response.class).value();
            if (status > 0) {
                builder.beginControlFlow("if(response.getStatus() == $L)", status);
                addResponseStatements(actionClass, builder, element);
                builder.endControlFlow();
            } else {
                addResponseStatements(actionClass, builder, element);
            }
        }
    }

    private void addResponseStatements(HttpActionClass actionClass, MethodSpec.Builder builder, Element element) {
        String fieldAddress = getFieldAddress(actionClass, element);
        if (equalTypes(element, ActionBody.class)) {
            builder.addStatement(fieldAddress + " = response.getBody()", element);
        } else if (equalTypes(element, String.class)) {
            builder.addStatement(fieldAddress + " = response.getBody().toString()", element);
        } else {
            builder.addStatement(fieldAddress + " = ($T) converter.fromBody(response.getBody(), new $T<$T>(){}.getType())", element, element.asType(), TypeToken.class, element.asType());
        }
    }

    private void addBasicHeadersMap(HttpActionClass actionClass, MethodSpec.Builder builder) {
        if (actionClass.getAnnotatedElements(ResponseHeader.class).isEmpty()) {
            return;
        }
        builder.addStatement("$T<$T, $T> $L = new $T<$T, $T>()", HashMap.class, String.class, String.class, BASE_HEADERS_MAP, HashMap.class, String.class, String.class);
        builder.beginControlFlow("for ($T header : response.getHeaders())", Header.class);
        builder.addStatement("$L.put(header.getName(), header.getValue())", BASE_HEADERS_MAP);
        builder.endControlFlow();
    }

    private void addResponseHeaders(HttpActionClass actionClass, MethodSpec.Builder builder) {
        for (Element element : actionClass.getAnnotatedElements(ResponseHeader.class)) {
            ResponseHeader annotation = element.getAnnotation(ResponseHeader.class);
            String fieldAddress = getFieldAddress(actionClass, element);
            builder.addStatement(fieldAddress + " = $L.make($S)", element.toString(), BASE_HEADERS_MAP, annotation.value());
        }
    }

    private void addStatusField(HttpActionClass actionClass, MethodSpec.Builder builder) {
        for (Element element : actionClass.getAnnotatedElements(Status.class)) {
            String fieldAddress = getFieldAddress(actionClass, element);
            if (TypeUtils.containsType(element, Boolean.class, boolean.class)) {
                builder.addStatement(fieldAddress + " = response.isSuccessful()", element);
            } else if (TypeUtils.containsType(element, Integer.class, int.class, long.class)) {
                builder.addStatement(fieldAddress + " = ($T) response.getStatus()", element, element.asType());
            } else if (equalTypes(element, String.class)) {
                builder.addStatement(fieldAddress + " = Integer.toString(response.getStatus())", element);
            } else if (TypeUtils.containsType(element, Long.class)) {
                builder.addStatement(fieldAddress + " = (long) response.getStatus()", element);
            }
        }
    }

    private static String getFieldAddress(HttpActionClass actionClass, Element element) {
        String address;
        if (actionClass.getTypeElement().equals(element.getEnclosingElement())) {
            address = "action.$L";
        } else {
            address = String.format("((%s)action).$L", element.getEnclosingElement());
        }
        return address;
    }

}