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
package io.github.jdaugherty.incrementalgroovy.benchmark

import groovy.transform.CompileStatic

/**
 * Generates a project laid out like a typical application on a persistence framework such as Grails' GORM:
 * <ul>
 *     <li>{@code transform}: a global AST transformation that reads the associations of persistent classes the way GORM
 *     does, by loading the classes of the associated entities compiled before</li>
 *     <li>{@code lib}: utilities and a trait, shared by the other modules</li>
 *     <li>{@code core}: entities in groups called areas, associated with entities of their area and with a few shared
 *     ones, the commands that bind them, and the base classes, traits and utilities they use</li>
 *     <li>{@code app}: the services and controllers of the entities</li>
 * </ul>
 * The same shape and seed generate the same sources.
 */
@CompileStatic
class ProjectGenerator {
    static final List<String> MODULES = ['lib', 'core', 'app']

    private static final int SHARED_ENTITIES = 10

    private final ProjectShape shape
    private final long seed

    ProjectGenerator(ProjectShape shape, long seed) {
        this.shape = shape
        this.seed = seed
    }

    GeneratedProject generate(File dir, Setup setup, File pluginRepository, String pluginVersion) {
        List<EntityModel> entities = entities(new Random(seed))
        Sources sources = new Sources(dir)
        buildFiles(sources, setup, pluginRepository, pluginVersion)
        transformModule(sources)
        libModule(sources)
        coreModule(sources, entities)
        appModule(sources, entities)

        EntityModel target = entities.find { it.service }
        return new GeneratedProject(dir, [
            appService : "app/src/main/groovy/${path(target.appPackage)}/${target.name}Service.groovy".toString(),
            coreEntity : "core/src/main/groovy/${path(target.corePackage)}/${target.name}.groovy".toString(),
            coreUtility: 'core/src/main/groovy/example/core/support/Dates.groovy',
            libUtility : 'lib/src/main/groovy/example/lib/Text.groovy',
        ])
    }

    /**
     * Decides everything random first, so that the sources depend on the seed only.
     */
    private List<EntityModel> entities(Random random) {
        List<EntityModel> entities = []
        for (int area = 0; area < shape.areas; area++) {
            List<EntityModel> inArea = []
            for (int i = 0; i < shape.entitiesPerArea; i++) {
                EntityModel entity = new EntityModel()
                entity.index = entities.size()
                entity.area = area
                inArea << entity
                entities << entity
            }
            for (EntityModel entity : inArea) {
                entity.base = random.nextBoolean() ? 'TrackedEntity' : 'BaseEntity'
                entity.describable = random.nextInt(100) < 30
                entity.usesText = random.nextInt(100) < 30
                entity.usesDates = random.nextInt(100) < 15
                entity.usesCodes = random.nextInt(100) < 5
                Set<EntityModel> associations = new LinkedHashSet<>()
                while (associations.size() < 2) {
                    EntityModel other = inArea[random.nextInt(inArea.size())]
                    if (other != entity) {
                        associations << other
                    }
                }
                entity.associations = associations.toList()
                entity.shared = random.nextBoolean() ? random.nextInt(SHARED_ENTITIES) : -1
                entity.command = random.nextBoolean()
                entity.commandUsesDates = random.nextInt(100) < 15
                entity.service = entity.index % 2 == 0
                entity.serviceUsesText = random.nextBoolean()
                entity.serviceUsesDates = random.nextInt(100) < 15
                entity.controllerUsesFormatting = random.nextInt(100) < 20
            }
            List<EntityModel> withService = inArea.findAll { it.service }
            for (EntityModel entity : withService) {
                if (random.nextInt(100) < 30) {
                    EntityModel other = withService[random.nextInt(withService.size())]
                    entity.serviceDependency = other == entity ? null : other
                }
            }
        }
        return entities
    }

    private static void buildFiles(Sources sources, Setup setup, File pluginRepository, String pluginVersion) {
        sources.write('settings.gradle', """\
            pluginManagement {
                repositories {
                    maven { url = uri('${pluginRepository.toURI()}') }
                }
            }

            // Gradle compares the compile classpath by its ABI rather than by its content
            enableFeaturePreview('GROOVY_COMPILATION_AVOIDANCE')

            rootProject.name = 'generated'
            include 'transform', 'lib', 'core', 'app'
            """.stripIndent())
        sources.write('gradle.properties', '''\
            org.gradle.jvmargs=-Xmx3g
            org.gradle.caching=false
            # global AST transformations are found through META-INF/services, which only the jar carries
            systemProp.org.gradle.java.compile-classpath-packaging=true
            '''.stripIndent())
        sources.write('build.gradle', """\
            plugins {
                ${setup.plugin ? "id 'io.github.jdaugherty.incremental-groovy' version '${pluginVersion}' apply false" : ''}
            }

            tasks.register('environment') {
                def groovyVersion = GroovySystem.version
                def javaVersion = System.getProperty('java.version')
                doLast {
                    println "groovy-version=\${groovyVersion}"
                    println "java-version=\${javaVersion}"
                }
            }
            """.stripIndent())
        sources.write('transform/build.gradle', '''\
            plugins {
                id 'groovy'
            }

            dependencies {
                implementation localGroovy()
            }
            '''.stripIndent())
        Map<String, String> dependencies = [
            lib : '',
            core: "api project(':lib')\n    api project(':transform')\n",
            app : "implementation project(':core')\n",
        ]
        dependencies.each { String module, String projectDependencies ->
            sources.write("${module}/build.gradle", """\
plugins {
    id 'groovy'
    id 'java-library'
${setup.plugin ? "    id 'io.github.jdaugherty.incremental-groovy'\n" : ''}}

dependencies {
    implementation localGroovy()
    ${projectDependencies}}

tasks.withType(GroovyCompile).configureEach {
    options.incremental = ${setup.incremental}
    groovyOptions.forkOptions.memoryMaximumSize = '2g'
}
""")
        }
    }

    private static void transformModule(Sources sources) {
        sources.write('transform/src/main/groovy/example/transform/Persistent.groovy', '''\
            package example.transform

            import java.lang.annotation.ElementType
            import java.lang.annotation.Retention
            import java.lang.annotation.RetentionPolicy
            import java.lang.annotation.Target

            /**
             * Marks a persistent class, whose associations PersistentTransformation reads.
             */
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.TYPE)
            @interface Persistent {
            }
            '''.stripIndent())
        sources.write('transform/src/main/groovy/example/transform/PersistentTransformation.groovy', '''\
            package example.transform

            import org.codehaus.groovy.ast.ASTNode
            import org.codehaus.groovy.ast.ClassNode
            import org.codehaus.groovy.ast.PropertyNode
            import org.codehaus.groovy.control.CompilePhase
            import org.codehaus.groovy.control.SourceUnit
            import org.codehaus.groovy.transform.ASTTransformation
            import org.codehaus.groovy.transform.GroovyASTTransformation

            import java.beans.Introspector

            /**
             * Reads the associations of the persistent classes it compiles the way persistence frameworks do: it loads the
             * class of each associated entity that was compiled before, and reads its bean properties.
             */
            @GroovyASTTransformation(phase = CompilePhase.CANONICALIZATION)
            class PersistentTransformation implements ASTTransformation {
                @Override
                void visit(ASTNode[] nodes, SourceUnit source) {
                    for (ClassNode classNode : source.AST.classes) {
                        if (!isPersistent(classNode)) {
                            continue
                        }
                        for (PropertyNode property : classNode.properties) {
                            ClassNode type = property.type
                            if (!type.isPrimaryClassNode() && type.isResolved() && isPersistent(type)) {
                                Introspector.getBeanInfo(getClass().classLoader.loadClass(type.name)).propertyDescriptors
                            }
                        }
                    }
                }

                private static boolean isPersistent(ClassNode classNode) {
                    classNode.annotations.any { it.classNode.name == Persistent.name }
                }
            }
            '''.stripIndent())
        sources.write('transform/src/main/resources/META-INF/services/org.codehaus.groovy.transform.ASTTransformation',
            'example.transform.PersistentTransformation\n')
    }

    private void libModule(Sources sources) {
        sources.write('lib/src/main/groovy/example/lib/Text.groovy', '''\
            package example.lib

            class Text {
                static String normalize(String value) {
                    String revision = 'rev-0'
                    return value == null ? revision : value.trim().toLowerCase()
                }

                static String title(String value) {
                    return value ? value[0].toUpperCase() + value.substring(1) : ''
                }
            }
            '''.stripIndent())
        sources.write('lib/src/main/groovy/example/lib/Audited.groovy', '''\
            package example.lib

            trait Audited {
                String createdBy
                String updatedBy

                String auditLabel() {
                    Text.normalize(createdBy) + '/' + Text.normalize(updatedBy)
                }
            }
            '''.stripIndent())
        sources.write('lib/src/main/groovy/example/lib/AuditRecord.groovy', '''\
            package example.lib

            class AuditRecord implements Audited {
                String note

                String render() {
                    Text.title(note) + auditLabel()
                }
            }
            '''.stripIndent())
        for (int i = 1; i <= shape.libUtilities; i++) {
            String name = utilityName(i)
            String call = i == 1 ? 'Text.title(value)' : "${utilityName(i - 1)}.apply(Text.title(value))"
            sources.write("lib/src/main/groovy/example/lib/${name}.groovy", """\
                package example.lib

                class ${name} {
                    static String apply(String value) {
                        ${call}
                    }

                    static int weight(String value) {
                        value?.length() ?: 0
                    }
                }
                """.stripIndent())
        }
    }

    private static String utilityName(int i) {
        return "Util${String.format('%02d', i)}"
    }

    private static void coreModule(Sources sources, List<EntityModel> entities) {
        String support = 'core/src/main/groovy/example/core/support'
        sources.write("${support}/Dates.groovy", '''\
            package example.core.support

            class Dates {
                static String stamp(Date date) {
                    String revision = 'rev-0'
                    return date == null ? revision : date.time.toString() + revision
                }
            }
            '''.stripIndent())
        sources.write("${support}/Codes.groovy", '''\
            package example.core.support

            class Codes {
                static String normalize(String code) {
                    String revision = 'rev-0'
                    return code?.trim()?.toUpperCase() ?: revision
                }
            }
            '''.stripIndent())
        sources.write("${support}/Describer.groovy", '''\
            package example.core.support

            class Describer {
                String describe(Object target) {
                    target.getClass().simpleName + '@' + Dates.stamp(new Date())
                }
            }
            '''.stripIndent())
        sources.write("${support}/Describable.groovy", '''\
            package example.core.support

            trait Describable {
                private Describer describer() {
                    new Describer()
                }

                String describe() {
                    describer().describe(this)
                }
            }
            '''.stripIndent())

        String entity = 'core/src/main/groovy/example/core/entity'
        sources.write("${entity}/BaseEntity.groovy", '''\
            package example.core.entity

            abstract class BaseEntity {
                Long id
                Long version
            }
            '''.stripIndent())
        sources.write("${entity}/TrackedEntity.groovy", '''\
            package example.core.entity

            import example.core.support.Dates

            abstract class TrackedEntity extends BaseEntity {
                Date dateCreated
                Date lastUpdated

                String created() {
                    Dates.stamp(dateCreated)
                }
            }
            '''.stripIndent())
        sources.write("${entity}/ReferenceEntity.groovy", '''\
            package example.core.entity

            abstract class ReferenceEntity extends BaseEntity {
                String code
            }
            '''.stripIndent())

        for (int i = 0; i < SHARED_ENTITIES; i++) {
            String name = sharedName(i)
            sources.write("core/src/main/groovy/example/core/shared/${name}.groovy", """\
                package example.core.shared

                import example.core.entity.ReferenceEntity
                import example.lib.Audited
                import example.lib.Text
                import example.transform.Persistent

                @Persistent
                class ${name} extends ReferenceEntity implements Audited {
                    String label

                    String display() {
                        Text.title(label) + code
                    }
                }
                """.stripIndent())
        }

        for (EntityModel model : entities) {
            String dir = "core/src/main/groovy/${path(model.corePackage)}"
            sources.write("${dir}/${model.name}.groovy", entitySource(model))
            if (model.command) {
                sources.write("${dir}/${model.name}Command.groovy", commandSource(model))
            }
        }
    }

    private static String sharedName(int i) {
        return "Shared${String.format('%02d', i)}"
    }

    private static String entitySource(EntityModel model) {
        Set<String> imports = new TreeSet<>(['example.lib.Audited', 'example.transform.Persistent', "example.core.entity.${model.base}".toString()])
        List<String> interfaces = ['Audited']
        if (model.describable) {
            imports << 'example.core.support.Describable'
            interfaces << 'Describable'
        }

        StringBuilder fields = new StringBuilder('    String name\n    BigDecimal amount\n    Integer quantity\n')
        for (EntityModel association : model.associations) {
            fields << "    ${association.name} ${association.fieldName}\n"
        }
        if (model.shared >= 0) {
            String shared = sharedName(model.shared)
            imports << "example.core.shared.${shared}".toString()
            fields << "    ${shared} ${shared.uncapitalize()}\n"
        }

        StringBuilder summary = new StringBuilder("        String revision = 'rev-0'\n        String text = name + ':' + quantity\n")
        if (model.usesText) {
            imports << 'example.lib.Text'
            summary << '        text += Text.normalize(name)\n'
        }
        if (model.usesDates) {
            imports << 'example.core.support.Dates'
            summary << '        text += Dates.stamp(new Date())\n'
        }
        if (model.usesCodes) {
            imports << 'example.core.support.Codes'
            summary << '        text += Codes.normalize(name)\n'
        }
        summary << '        return text + revision\n'

        return """\
package ${model.corePackage}

${imports.collect { "import ${it}" }.join('\n')}

@Persistent
class ${model.name} extends ${model.base} implements ${interfaces.join(', ')} {
${fields}
    String summary() {
${summary}    }

    BigDecimal total() {
        (amount ?: 0) * (quantity ?: 0) + (${model.associations[0].fieldName}?.amount ?: 0)
    }
}
"""
    }

    private static String commandSource(EntityModel model) {
        return """\
package ${model.corePackage}
${model.commandUsesDates ? '\nimport example.core.support.Dates\n' : ''}
class ${model.name}Command {
    Long id
    String name
    ${model.name} entity

    boolean validate() {
        String revision = 'rev-0'
        return name != null && entity?.name != revision${model.commandUsesDates ? ' && Dates.stamp(new Date()) != revision' : ''}
    }
}
"""
    }

    private static void appModule(Sources sources, List<EntityModel> entities) {
        sources.write('app/src/main/groovy/example/app/support/Formatting.groovy', '''\
            package example.app.support

            import example.lib.Text

            class Formatting {
                static String title(String value) {
                    String revision = 'rev-0'
                    return Text.title(value) + revision
                }
            }
            '''.stripIndent())
        for (EntityModel model : entities.findAll { it.service }) {
            String dir = "app/src/main/groovy/${path(model.appPackage)}"
            sources.write("${dir}/${model.name}Service.groovy", serviceSource(model))
            sources.write("${dir}/${model.name}Controller.groovy", controllerSource(model))
        }
    }

    private static String serviceSource(EntityModel model) {
        Set<String> imports = new TreeSet<>(["${model.corePackage}.${model.name}".toString()])
        StringBuilder report = new StringBuilder("        String revision = 'rev-0'\n        String text = entity.summary() + entity.total()\n")
        if (model.serviceUsesText) {
            imports << 'example.lib.Text'
            report << '        text += Text.normalize(entity.name)\n'
        }
        if (model.serviceUsesDates) {
            imports << 'example.core.support.Dates'
            report << '        text += Dates.stamp(new Date())\n'
        }
        String dependencyField = ''
        if (model.serviceDependency != null) {
            String other = model.serviceDependency.fieldName + 'Service'
            dependencyField = "    ${model.serviceDependency.name}Service ${other}\n\n"
            report << "        text += ${other}.report(${other}.create(entity.name))\n"
        }
        report << '        return text + revision\n'
        return """\
package ${model.appPackage}

${imports.collect { "import ${it}" }.join('\n')}

class ${model.name}Service {
${dependencyField}    ${model.name} create(String name) {
        new ${model.name}(name: name)
    }

    String report(${model.name} entity) {
${report}    }
}
"""
    }

    private static String controllerSource(EntityModel model) {
        String service = "${model.fieldName}Service"
        String title = model.controllerUsesFormatting ? "Formatting.title('${model.fieldName}')" : "'${model.fieldName}'"
        return """\
package ${model.appPackage}

${model.controllerUsesFormatting ? 'import example.app.support.Formatting\n' : ''}import ${model.corePackage}.${model.name}

class ${model.name}Controller {
    ${model.name}Service ${service}

    Map show(String name) {
        ${model.name} entity = ${service}.create(name)
        return [entity: entity, report: ${service}.report(entity)]
    }

    Map index() {
        return [title: ${title}]
    }
}
"""
    }

    private static String path(String packageName) {
        return packageName.replace('.', '/')
    }

    /**
     * An entity of {@code core}, with its command, and its service and controller in {@code app}.
     */
    private static class EntityModel {
        int index
        int area
        String base
        boolean describable
        boolean usesText
        boolean usesDates
        boolean usesCodes
        List<EntityModel> associations
        /** the shared entity it is associated with, or -1 */
        int shared
        boolean command
        boolean commandUsesDates
        boolean service
        boolean serviceUsesText
        boolean serviceUsesDates
        EntityModel serviceDependency
        boolean controllerUsesFormatting

        String getName() {
            return "Entity${String.format('%04d', index)}"
        }

        String getFieldName() {
            return name.uncapitalize()
        }

        String getCorePackage() {
            return "example.core.${areaName}"
        }

        String getAppPackage() {
            return "example.app.${areaName}"
        }

        private String getAreaName() {
            return "area${String.format('%02d', area)}"
        }
    }

    private static class Sources {
        final File dir

        Sources(File dir) {
            this.dir = dir
        }

        void write(String path, String content) {
            File file = new File(dir, path)
            file.parentFile.mkdirs()
            file.setText(content, 'UTF-8')
        }
    }
}
