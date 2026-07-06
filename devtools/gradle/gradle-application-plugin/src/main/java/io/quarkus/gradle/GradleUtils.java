
package io.quarkus.gradle;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.attributes.Category;
import org.gradle.api.file.FileTree;
import org.gradle.api.plugins.JavaPlugin;

public class GradleUtils {

    // Keep in sync with io.quarkus.devservices.deployment.compose.ComposeDevServicesProcessor
    private static final Pattern COMPOSE_FILE = Pattern.compile("(^docker-compose|^compose)(-dev(-)?service).*.(yml|yaml)");

    public static List<Dependency> listProjectBoms(Project project) {
        final Configuration impl = project.getConfigurations().getByName(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME);
        List<Dependency> boms = new ArrayList<>();
        impl.getIncoming().getDependencies()
                .forEach(d -> {
                    if (!(d instanceof ModuleDependency)) {
                        return;
                    }
                    final ModuleDependency module = (ModuleDependency) d;
                    final Category category = module.getAttributes().getAttribute(Category.CATEGORY_ATTRIBUTE);
                    if (category != null
                            && (Category.ENFORCED_PLATFORM.equals(category.getName())
                                    || Category.REGULAR_PLATFORM.equals(category.getName()))) {
                        boms.add(d);
                    }
                });
        return boms;
    }

    public static FileTree composeDevFiles(Project project) {
        return project.getLayout()
                .getProjectDirectory()
                .getAsFileTree()
                .matching(p -> p.include(element -> COMPOSE_FILE.matcher(element.getName()).matches()));
    }
}
