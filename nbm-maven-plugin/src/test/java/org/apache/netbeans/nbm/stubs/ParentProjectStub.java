/*
 * Copyright 2024 The Apache Software Foundation.
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
package org.apache.netbeans.nbm.stubs;

import java.io.File;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.plugin.testing.stubs.ArtifactStub;
import org.apache.maven.project.MavenProject;

/**
 *
 * @author barboni
 */
class ParentProjectStub extends MavenProject {

    public ParentProjectStub() {
        setArtifactId("myname");
        setGroupId("foo");
        setVersion("1.2.3.4");
        setArtifact(new ArtifactStub() {
            @Override
            public String getArtifactId() {
                return "myname";
            }

            @Override
            public String getGroupId() {
                return "foo";
            }

            @Override
            public String getVersion() {
                return "1.2.3.4";
            }

            @Override
            public VersionRange getVersionRange() {
                return VersionRange.createFromVersion(getVersion());
            }

        });
    }

    @Override
    public File getBasedir() {
        return new File(super.getBasedir() + "/src/test/resources/unit/build-installer-nbm-application/");
    }
}
