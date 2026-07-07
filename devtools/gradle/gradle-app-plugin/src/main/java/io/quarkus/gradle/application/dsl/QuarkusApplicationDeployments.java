package io.quarkus.gradle.application.dsl;

import javax.inject.Inject;

import org.gradle.api.Action;
import org.gradle.api.ExtensiblePolymorphicDomainObjectContainer;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.model.ObjectFactory;

public class QuarkusApplicationDeployments {

    private final ExtensiblePolymorphicDomainObjectContainer<QuarkusApplicationDeployment> container;

    @Inject
    public QuarkusApplicationDeployments(ObjectFactory objects) {
        this.container = objects.polymorphicDomainObjectContainer(QuarkusApplicationDeployment.class);
        registerFactories(objects);
    }

    private void registerFactories(ObjectFactory objects) {
        container.registerFactory(QuarkusKubernetesDeployment.class,
                name -> objects.newInstance(QuarkusKubernetesDeployment.class, name));
        container.registerFactory(QuarkusOpenShiftDeployment.class,
                name -> objects.newInstance(QuarkusOpenShiftDeployment.class, name));
        container.registerFactory(QuarkusKnativeDeployment.class,
                name -> objects.newInstance(QuarkusKnativeDeployment.class, name));
        container.registerFactory(QuarkusKindDeployment.class,
                name -> objects.newInstance(QuarkusKindDeployment.class, name));
        container.registerFactory(QuarkusMinikubeDeployment.class,
                name -> objects.newInstance(QuarkusMinikubeDeployment.class, name));
    }

    public NamedDomainObjectProvider<QuarkusKubernetesDeployment> kubernetes(String name) {
        return container.register(name, QuarkusKubernetesDeployment.class);
    }

    public NamedDomainObjectProvider<QuarkusKubernetesDeployment> kubernetes(String name,
            Action<? super QuarkusKubernetesDeployment> action) {
        return container.register(name, QuarkusKubernetesDeployment.class, action);
    }

    public NamedDomainObjectProvider<QuarkusOpenShiftDeployment> openshift(String name) {
        return container.register(name, QuarkusOpenShiftDeployment.class);
    }

    public NamedDomainObjectProvider<QuarkusOpenShiftDeployment> openshift(String name,
            Action<? super QuarkusOpenShiftDeployment> action) {
        return container.register(name, QuarkusOpenShiftDeployment.class, action);
    }

    public NamedDomainObjectProvider<QuarkusKnativeDeployment> knative(String name) {
        return container.register(name, QuarkusKnativeDeployment.class);
    }

    public NamedDomainObjectProvider<QuarkusKnativeDeployment> knative(String name,
            Action<? super QuarkusKnativeDeployment> action) {
        return container.register(name, QuarkusKnativeDeployment.class, action);
    }

    public NamedDomainObjectProvider<QuarkusKindDeployment> kind(String name) {
        return container.register(name, QuarkusKindDeployment.class);
    }

    public NamedDomainObjectProvider<QuarkusKindDeployment> kind(String name,
            Action<? super QuarkusKindDeployment> action) {
        return container.register(name, QuarkusKindDeployment.class, action);
    }

    public NamedDomainObjectProvider<QuarkusMinikubeDeployment> minikube(String name) {
        return container.register(name, QuarkusMinikubeDeployment.class);
    }

    public NamedDomainObjectProvider<QuarkusMinikubeDeployment> minikube(String name,
            Action<? super QuarkusMinikubeDeployment> action) {
        return container.register(name, QuarkusMinikubeDeployment.class, action);
    }

    public void all(Action<? super QuarkusApplicationDeployment> action) {
        container.all(action);
    }
}
