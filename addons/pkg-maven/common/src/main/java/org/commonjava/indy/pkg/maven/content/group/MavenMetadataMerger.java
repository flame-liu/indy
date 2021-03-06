/**
 * Copyright (C) 2011-2018 Red Hat, Inc. (https://github.com/Commonjava/indy)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commonjava.indy.pkg.maven.content.group;

import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.SnapshotVersion;
import org.apache.maven.artifact.repository.metadata.Versioning;
import org.commonjava.atlas.maven.ident.util.VersionUtils;
import org.commonjava.atlas.maven.ident.version.InvalidVersionSpecificationException;
import org.commonjava.atlas.maven.ident.version.SingleVersion;
import org.commonjava.indy.model.core.Group;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@ApplicationScoped
public class MavenMetadataMerger
{

    private static final class SnapshotVersionComparator
            implements Comparator<SnapshotVersion>
    {
        @Override
        public int compare( final SnapshotVersion first, final SnapshotVersion second )
        {
            int comp = first.getVersion()
                            .compareTo( second.getVersion() );
            if ( comp == 0 )
            {
                comp = first.getExtension()
                            .compareTo( second.getExtension() );
            }

            return comp;
        }
    }

    public static final String METADATA_NAME = "maven-metadata.xml";

    public static final String METADATA_SHA_NAME = METADATA_NAME + ".sha1";

    public static final String METADATA_SHA256_NAME = METADATA_NAME + ".sha256";

    public static final String METADATA_MD5_NAME = METADATA_NAME + ".md5";

    public Metadata mergeFromMetadatas( final Metadata master, final Collection<Metadata> sources, final Group group, final String path )
    {
        Logger logger = LoggerFactory.getLogger( getClass() );
        logger.debug( "Generating merged metadata in: {}:{}", group.getKey(), path );

        if ( master.getVersioning() == null )
        {
            master.setVersioning( new Versioning() );
        }

        boolean merged = false;
        for ( final Metadata src : sources )
        {

            logger.trace( "Adding in metadata content from: {}", src );

            // there is a lot of junk in here to make up for Metadata's anemic merge() method.
            if ( src.getGroupId() != null )
            {
                master.setGroupId( src.getGroupId() );
            }

            if ( src.getArtifactId() != null )
            {
                master.setArtifactId( src.getArtifactId() );
            }

            if ( src.getVersion() != null )
            {
                master.setVersion( src.getVersion() );
            }

            master.merge( src );

            Versioning versioning = master.getVersioning();
            Versioning mdVersioning = src.getVersioning();

            // FIXME: Should we try to merge snapshot lists instead of using the first one we encounter??
            if ( versioning.getSnapshot() == null && mdVersioning != null )
            {
                logger.trace( "INCLUDING snapshot information from: {} in: {}:{}", src, group.getKey(), path );

                versioning.setSnapshot( mdVersioning.getSnapshot() );

                final List<SnapshotVersion> snapshotVersions = versioning.getSnapshotVersions();
                boolean added = false;
                for ( final SnapshotVersion snap : mdVersioning.getSnapshotVersions() )
                {
                    if ( !snapshotVersions.contains( snap ) )
                    {
                        snapshotVersions.add( snap );
                        added = true;
                    }
                }

                if ( added )
                {
                    snapshotVersions.sort( new SnapshotVersionComparator() );
                }
            }
            else
            {
                logger.warn( "SKIPPING snapshot information from: {} in: {}:{})", src, group.getKey(), path );
            }

            merged = true;
        }

        if ( merged )
        {
            return master;
        }

        return null;
    }

    public void sortVersions( Metadata metadata )
    {
        Versioning versioning = metadata.getVersioning();
        if ( versioning != null && versioning.getVersions() != null )
        {

            List<SingleVersion> versionObjects =
                    versioning.getVersions().stream().map( (v)->{
                        try
                        {
                            return VersionUtils.createSingleVersion( v );
                        }
                        catch (InvalidVersionSpecificationException e )
                        {
                            return null;
                        }
                    } ).filter( Objects::nonNull ).collect( Collectors.toList() );

            Collections.sort( versionObjects );

            versioning.setVersions(
                    versionObjects.stream().map( SingleVersion::renderStandard ).collect( Collectors.toList() ) );

            if ( versionObjects.size() > 0 )
            {
                String latest = versionObjects.get( versionObjects.size() - 1 ).renderStandard();
                versioning.setLatest( latest );
                versioning.setRelease( latest );
            }
        }
    }

}
