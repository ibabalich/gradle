/*
 * Copyright 2011 the original author or authors.
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

package org.gradle.tooling.internal.provider;

import org.gradle.api.Project;
import org.gradle.api.internal.GradleInternal;
import org.gradle.plugins.ide.idea.IdeaPlugin;
import org.gradle.plugins.ide.idea.model.*;
import org.gradle.tooling.internal.impl.idea.*;
import org.gradle.tooling.internal.protocol.InternalIdeaProject;
import org.gradle.tooling.internal.protocol.ProjectVersion3;
import org.gradle.tooling.model.GradleProject;
import org.gradle.tooling.model.idea.IdeaDependency;
import org.gradle.tooling.model.idea.IdeaSourceDirectory;

import java.io.File;
import java.util.*;

/**
 * @author: Szczepan Faber, created at: 7/23/11
 */
public class IdeaModelBuilder implements BuildsModel {
    public boolean canBuild(Class<?> type) {
        return type == InternalIdeaProject.class;
    }

    private final GradleProjectBuilder gradleProjectBuilder = new GradleProjectBuilder();
    private boolean offlineDependencyResolution;

    public ProjectVersion3 buildAll(GradleInternal gradle) {
        Project root = gradle.getRootProject();
        applyIdeaPlugin(root);
        GradleProject rootGradleProject = gradleProjectBuilder.buildAll(gradle);
        return build(root, rootGradleProject);
    }

    private void applyIdeaPlugin(Project root) {
        Set<Project> allprojects = root.getAllprojects();
        for (Project p : allprojects) {
            p.getPlugins().apply(IdeaPlugin.class);
        }
        root.getPlugins().getPlugin(IdeaPlugin.class).makeSureModuleNamesAreUnique();
    }

    private ProjectVersion3 build(Project project, GradleProject rootGradleProject) {
        IdeaModel ideaModel = project.getPlugins().getPlugin(IdeaPlugin.class).getModel();
        IdeaProject projectModel = ideaModel.getProject();

        DefaultIdeaProject out = new DefaultIdeaProject()
                .setName(projectModel.getName())
                .setJdkName(projectModel.getJdkName())
                .setLanguageLevel(new DefaultIdeaLanguageLevel(projectModel.getLanguageLevel().getLevel()));

        Map<String, DefaultIdeaModule> modules = new HashMap<String, DefaultIdeaModule>();
        for (IdeaModule module : projectModel.getModules()) {
            appendModule(modules, module, out, rootGradleProject);
        }
        for (IdeaModule module : projectModel.getModules()) {
            buildDependencies(modules, module);
        }
        out.setChildren(new LinkedList<DefaultIdeaModule>(modules.values()));

        return out;
    }

    private void buildDependencies(Map<String, DefaultIdeaModule> modules, IdeaModule ideaModule) {
        ideaModule.setOffline(offlineDependencyResolution);
        Set<Dependency> resolved = ideaModule.resolveDependencies();
        List<IdeaDependency> dependencies = new LinkedList<IdeaDependency>();
        for (Dependency dependency : resolved) {
            if (dependency instanceof SingleEntryModuleLibrary) {
                SingleEntryModuleLibrary d = (SingleEntryModuleLibrary) dependency;
                IdeaDependency defaultDependency = new DefaultIdeaSingleEntryLibraryDependency()
                        .setFile(d.getLibraryFile())
                        .setSource(d.getSourceFile())
                        .setJavadoc(d.getJavadocFile())
                        .setScope(new DefaultIdeaDependencyScope(d.getScope()))
                        .setExported(d.getExported());
                dependencies.add(defaultDependency);
            } else if (dependency instanceof ModuleDependency) {
                ModuleDependency d = (ModuleDependency) dependency;
                IdeaDependency defaultDependency = new DefaultIdeaModuleDependency()
                        .setExported(d.getExported())
                        .setScope(new DefaultIdeaDependencyScope(d.getScope()))
                        .setDependencyModule(modules.get(d.getName()));
                dependencies.add(defaultDependency);
            }
        }
        modules.get(ideaModule.getName()).setDependencies(dependencies);
    }

    private void appendModule(Map<String, DefaultIdeaModule> modules, IdeaModule ideaModule, DefaultIdeaProject ideaProject, GradleProject rootGradleProject) {
        DefaultIdeaContentRoot contentRoot = new DefaultIdeaContentRoot()
            .setRootDirectory(ideaModule.getContentRoot())
            .setSourceDirectories(srcDirs(ideaModule.getSourceDirs()))
            .setTestDirectories(srcDirs(ideaModule.getTestSourceDirs()))
            .setExcludeDirectories(ideaModule.getExcludeDirs());

        DefaultIdeaModule defaultIdeaModule = new DefaultIdeaModule()
                .setName(ideaModule.getName())
                .setParent(ideaProject)
                .setGradleProject(rootGradleProject.findByPath(ideaModule.getProject().getPath()))
                .setModuleFileDir(ideaModule.getIml().getGenerateTo())
                .setContentRoots(Collections.singletonList(contentRoot))
                .setCompilerOutput(new DefaultIdeaCompilerOutput()
                    .setInheritOutputDirs(ideaModule.getInheritOutputDirs() != null ? ideaModule.getInheritOutputDirs() : false)
                    .setOutputDir(ideaModule.getOutputDir())
                    .setTestOutputDir(ideaModule.getTestOutputDir())
                );

        modules.put(ideaModule.getName(), defaultIdeaModule);
    }

    private Set<IdeaSourceDirectory> srcDirs(Set<File> sourceDirs) {
        Set<IdeaSourceDirectory> out = new LinkedHashSet<IdeaSourceDirectory>();
        for (File s : sourceDirs) {
            out.add(new DefaultIdeaSourceDirectory().setDirectory(s));
        }
        return out;
    }

    public IdeaModelBuilder setOfflineDependencyResolution(boolean offlineDependencyResolution) {
        this.offlineDependencyResolution = offlineDependencyResolution;
        return this;
    }
}
