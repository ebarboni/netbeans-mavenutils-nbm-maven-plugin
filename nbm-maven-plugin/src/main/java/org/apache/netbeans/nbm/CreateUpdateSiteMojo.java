package org.apache.netbeans.nbm;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
import java.io.File;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.DefaultArtifactRepository;
import org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.taskdefs.Copy;
import org.apache.tools.ant.types.FileSet;
import org.codehaus.plexus.archiver.gzip.GZipArchiver;
import org.netbeans.nbbuild.MakeUpdateDesc;

/**
 * Create the NetBeans auto update site definition.
 *
 * @author Milos Kleint
 *
 */
@Mojo(name = "autoupdate",
        defaultPhase = LifecyclePhase.PACKAGE,
        aggregator = true,
        requiresDependencyResolution = ResolutionScope.RUNTIME)
public class CreateUpdateSiteMojo
        extends AbstractNbmMojo {

    /**
     * output directory.
     */
    @Parameter(required = true, defaultValue = "${project.build.directory}")
    protected File outputDirectory;
    /**
     * autoupdate site xml file name.
     */
    @Parameter(defaultValue = "updates.xml", property = "maven.nbm.updatesitexml")
    protected String fileName;
    /**
     * A custom distribution base for the nbms in the update site. If NOT
     * defined, the update site will use a simple relative URL, which is
     * generally what you want. Defining it as "auto" will pick up the
     * distribution URL from each NBM, which is generally wrong. See
     * <code>distributionUrl</code> in nbm mojo for what url will be used in
     * that case.
     * <p/>
     * The value is either a direct http protocol based URL that points to the
     * location under which all nbm files are located, or
     * <p/>
     * allows to create an update site based on maven repository content. The
     * resulting autoupdate site document can be uploaded as tar.gz to
     * repository as well as attached artifact to the 'nbm-application' project.
     * <br/>
     * Format: id::layout::url same as in maven-deploy-plugin
     * <br/>
     * with the 'default' and 'legacy' layouts. (maven2 vs maven1 layout)
     * <br/>
     * If the value doesn't contain :: characters, it's assumed to be the flat
     * structure and the value is just the URL.
     *
     * @since 3.0 it's also possible to add remote repository as base
     */
    @Parameter(defaultValue = ".", property = "maven.nbm.customDistBase")
    private String distBase;

    /**
     * The Maven Project.
     *
     */
    @Parameter(required = true, readonly = true, property = "project")
    private MavenProject project;

    /**
     * If the executed project is a reactor project, this will contains the full
     * list of projects in the reactor.
     */
    @Parameter(required = true, readonly = true, defaultValue = "${reactorProjects}")
    private List reactorProjects;

    /**
     * List of Ant style patterns on artifact GA (groupID:artifactID) that
     * should be included in the update site. Eg. org.netbeans.* matches all
     * artifacts with any groupID starting with 'org.netbeans.', org.*:api will
     * match any artifact with artifactId of 'api' and groupId starting with
     * 'org.'
     *
     * @since 3.14
     */
    @Parameter
    private List<String> updateSiteIncludes;

    private final ArtifactFactory artifactFactory;

    /**
     * Used for attaching the artifact in the project
     *
     */
    private final MavenProjectHelper projectHelper;

    private final ArtifactResolver artifactResolver;

    /**
     * Local maven repository.
     *
     */
    @Parameter(defaultValue = "${session}", required = true, readonly = true)
    protected MavenSession session;

    private final Map<String, ArtifactRepositoryLayout> layouts;

    @Inject
    public CreateUpdateSiteMojo(ArtifactFactory artifactFactory, MavenProjectHelper projectHelper, ArtifactResolver artifactResolver, Map<String, ArtifactRepositoryLayout> layouts) {
        this.artifactFactory = artifactFactory;
        this.projectHelper = projectHelper;
        this.artifactResolver = artifactResolver;
        this.layouts = layouts;
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        Project antProject = registerNbmAntTasks();
        File nbmBuildDirFile = new File(outputDirectory, "netbeans_site");
        if (!nbmBuildDirFile.exists()) {
            nbmBuildDirFile.mkdirs();
        }

        boolean isRepository = false;
        if ("auto".equals(distBase)) {
            distBase = null;
        }
        ArtifactRepository distRepository = getDeploymentRepository(distBase, layouts);
        String oldDistBase = null;
        if (distRepository != null) {
            isRepository = true;
        } else {
            if (distBase != null && !distBase.contains("::")) {
                oldDistBase = distBase;
            }
        }

        if ("nbm-application".equals(project.getPackaging())) {
            @SuppressWarnings("unchecked")
            Set<Artifact> artifacts = project.getArtifacts();
            for (Artifact art : artifacts) {
                if (!matchesIncludes(art)) {
                    continue;
                }
                ArtifactResult res
                        = turnJarToNbmFile(art, artifactFactory, artifactResolver, project, session.getLocalRepository());
                if (res.hasConvertedArtifact()) {
                    art = res.getConvertedArtifact();
                }

                if (art.getType().equals("nbm-file")) {
                    Copy copyTask = (Copy) antProject.createTask("copy");
                    copyTask.setOverwrite(true);
                    copyTask.setFile(art.getFile());
                    if (!isRepository) {
                        copyTask.setFlatten(true);
                        copyTask.setTodir(nbmBuildDirFile);
                    } else {
                        String path = distRepository.pathOf(art);
                        File f = new File(nbmBuildDirFile, path.replace('/', File.separatorChar));
                        copyTask.setTofile(f);
                    }
                    try {
                        copyTask.execute();
                    } catch (BuildException ex) {
                        throw new MojoExecutionException("Cannot merge nbm files into autoupdate site", ex);
                    }

                }
                if (res.isOSGiBundle()) {
                    // TODO check for bundles
                }
            }
            getLog().info("Created NetBeans module cluster(s) at " + nbmBuildDirFile.getAbsoluteFile());

        } else if (reactorProjects != null && reactorProjects.size() > 0) {

            Iterator it = reactorProjects.iterator();
            while (it.hasNext()) {
                MavenProject proj = (MavenProject) it.next();
                File projOutputDirectory = new File(proj.getBuild().getDirectory());
                if (projOutputDirectory != null && projOutputDirectory.exists()) {
                    Copy copyTask = (Copy) antProject.createTask("copy");
                    if (!isRepository) {
                        FileSet fs = new FileSet();
                        fs.setDir(projOutputDirectory);
                        fs.createInclude().setName("*.nbm");
                        copyTask.addFileset(fs);
                        copyTask.setOverwrite(true);
                        copyTask.setFlatten(true);
                        copyTask.setTodir(nbmBuildDirFile);
                    } else {
                        boolean has = false;
                        File[] fls = projOutputDirectory.listFiles();
                        if (fls != null) {
                            for (File fl : fls) {
                                if (fl.getName().endsWith(".nbm")) {
                                    copyTask.setFile(fl);
                                    has = true;
                                    break;
                                }
                            }
                        }
                        if (!has) {
                            continue;
                        }
                        Artifact art
                                = artifactFactory.createArtifact(proj.getGroupId(), proj.getArtifactId(), proj.
                                        getVersion(),
                                        null, "nbm-file");
                        String path = distRepository.pathOf(art);
                        File f = new File(nbmBuildDirFile, path.replace('/', File.separatorChar));
                        copyTask.setTofile(f);
                    }
                    try {
                        copyTask.execute();
                    } catch (BuildException ex) {
                        throw new MojoExecutionException("Cannot merge nbm files into autoupdate site", ex);
                    }
                }
            }
        } else {
            throw new MojoExecutionException(
                    "This goal only makes sense on reactor projects or project with 'nbm-application' packaging.");

        }
        MakeUpdateDesc descTask = (MakeUpdateDesc) antProject.createTask("updatedist");
        File xmlFile = new File(nbmBuildDirFile, fileName);
        descTask.setDesc(xmlFile);
        if (oldDistBase != null) {
            descTask.setDistBase(oldDistBase);
        }
        if (distRepository != null) {
            descTask.setDistBase(distRepository.getUrl());
        }
        FileSet fs = new FileSet();
        fs.setDir(nbmBuildDirFile);
        fs.createInclude().setName("**/*.nbm");
        descTask.addFileset(fs);
        try {
            descTask.execute();
        } catch (BuildException ex) {
            throw new MojoExecutionException("Cannot create autoupdate site xml file", ex);
        }
        getLog().info("Generated autoupdate site content at " + nbmBuildDirFile.getAbsolutePath());

        try {
            GZipArchiver gz = new GZipArchiver();
            gz.addFile(xmlFile, fileName);
            File gzipped = new File(nbmBuildDirFile, fileName + ".gz");
            gz.setDestFile(gzipped);
            gz.createArchive();
            if ("nbm-application".equals(project.getPackaging())) {
                projectHelper.attachArtifact(project, "xml.gz", "updatesite", gzipped);
            }
        } catch (Exception ex) {
            throw new MojoExecutionException("Cannot create gzipped version of the update site xml file.", ex);
        }

    }

    private static final Pattern ALT_REPO_SYNTAX_PATTERN = Pattern.compile("(.+)::(.+)::(.+)");

    static ArtifactRepository getDeploymentRepository(String distBase, Map<String, ArtifactRepositoryLayout> layouts)
            throws MojoExecutionException, MojoFailureException {

        ArtifactRepository repo = null;

        if (distBase != null) {

            Matcher matcher = ALT_REPO_SYNTAX_PATTERN.matcher(distBase);

            if (!matcher.matches()) {
                if (!distBase.contains("::")) {
                    //backward compatibility gag.
                    return null;
                }
                throw new MojoFailureException(distBase,
                        "Invalid syntax for repository.",
                        "Invalid syntax for alternative repository. Use \"id::layout::url\".");
            } else {
                String id = matcher.group(1).trim();
                String layout = matcher.group(2).trim();
                String url = matcher.group(3).trim();

                ArtifactRepositoryLayout repoLayout = layouts.get(layout);
                if (repoLayout == null) {
                    throw new MojoExecutionException("Cannot find repository layout: " + layout);
                }

                repo = new DefaultArtifactRepository(id, url, repoLayout);
            }
        }
        return repo;
    }

    private boolean matchesIncludes(Artifact art) {
        if (updateSiteIncludes != null) {
            String s = art.getGroupId() + ":" + art.getArtifactId();
            for (String p : updateSiteIncludes) {
                //TODO optimize and only do once per execution.
                p = p.replace(".", "\\.").replace("*", ".*");
                Pattern patt = Pattern.compile(p);
                if (patt.matcher(s).matches()) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }
}
