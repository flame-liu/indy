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
package org.commonjava.indy.core.bind.jaxrs.admin;

import static javax.ws.rs.core.Response.Status.CONFLICT;
import static javax.ws.rs.core.Response.noContent;
import static javax.ws.rs.core.Response.notModified;
import static javax.ws.rs.core.Response.ok;
import static javax.ws.rs.core.Response.status;
import static org.apache.commons.lang.StringUtils.isEmpty;
import static org.commonjava.indy.bind.jaxrs.util.ResponseUtils.formatCreatedResponseWithJsonEntity;
import static org.commonjava.indy.bind.jaxrs.util.ResponseUtils.formatOkResponseWithJsonEntity;
import static org.commonjava.indy.bind.jaxrs.util.ResponseUtils.formatResponse;
import static org.commonjava.indy.model.core.ArtifactStore.METADATA_CHANGELOG;
import static org.commonjava.indy.util.ApplicationContent.application_json;

import java.io.IOException;
import java.net.URI;
import java.util.List;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HEAD;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.apache.commons.io.IOUtils;
import org.commonjava.indy.IndyWorkflowException;
import org.commonjava.indy.bind.jaxrs.IndyResources;
import org.commonjava.indy.bind.jaxrs.SecurityManager;
import org.commonjava.indy.bind.jaxrs.util.ResponseUtils;
import org.commonjava.indy.core.ctl.AdminController;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.model.core.dto.StoreListingDTO;
import org.commonjava.indy.model.core.io.IndyObjectMapper;
import org.commonjava.indy.util.ApplicationContent;
import org.commonjava.atlas.maven.ident.util.JoinString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Api( description = "Resource for accessing and managing artifact store definitions", value = "Store Administration" )
@Path( "/api/admin/stores/{packageType}/{type: (hosted|group|remote)}" )
@ApplicationScoped
public class StoreAdminHandler
    implements IndyResources
{

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    private AdminController adminController;

    @Inject
    private IndyObjectMapper objectMapper;

    @Inject
    private SecurityManager securityManager;

    public StoreAdminHandler()
    {
        logger.info( "\n\n\n\nStarted StoreAdminHandler\n\n\n\n" );
    }

    //    @Context
    //    private UriInfo uriInfo;
    //
    //    @Context
    //    private HttpServletRequest request;

    @ApiOperation( "Check if a given store exists" )
    @ApiResponses( { @ApiResponse( code = 200, message = "The store exists" ),
                           @ApiResponse( code = 404, message = "The store doesn't exist" ) } )
    @Path( "/{name}" )
    @HEAD
    public Response exists( final @PathParam( "packageType" ) String packageType,
                            final @ApiParam( allowableValues = "hosted,group,remote", required = true )
                            @PathParam( "type" ) String type,
                            @ApiParam( required = true ) @PathParam( "name" ) final String name )
    {
        Response response;
        final StoreType st = StoreType.get( type );

        logger.info( "Checking for existence of: {}:{}:{}", packageType, st, name );

        if ( adminController.exists( new StoreKey( packageType, st, name ) ) )
        {

            logger.info( "returning OK" );
            response = Response.ok().build();
        }
        else
        {
            logger.info( "Returning NOT FOUND" );
            response = Response.status( Status.NOT_FOUND ).build();
        }
        return response;
    }

    @ApiOperation( "Create a new store" )
    @ApiResponses( { @ApiResponse( code = 201, response = ArtifactStore.class, message = "The store was created" ),
        @ApiResponse( code = 409, message = "A store with the specified type and name already exists" ) } )
    @ApiImplicitParams( { @ApiImplicitParam( allowMultiple = false, paramType = "body", name = "body", required = true, dataType = "org.commonjava.indy.model.core.ArtifactStore", value = "The artifact store definition JSON" ) } )
    @POST
    @Consumes( ApplicationContent.application_json )
    @Produces( ApplicationContent.application_json )
    public Response create( final @PathParam( "packageType" ) String packageType,
                            final @ApiParam( allowableValues = "hosted,group,remote", required = true ) @PathParam( "type" ) String type,
                            final @Context UriInfo uriInfo,
                            final @Context HttpServletRequest request,
                            final @Context SecurityContext securityContext )
    {
        final StoreType st = StoreType.get( type );

        Response response = null;
        String json = null;
        try
        {
            json = IOUtils.toString( request.getInputStream() );
            json = objectMapper.patchLegacyStoreJson( json );
        }
        catch ( final IOException e )
        {
            final String message = "Failed to read " + st.getStoreClass()
                                                         .getSimpleName() + " from request body.";

            logger.error( message, e );
            response = formatResponse( e, message );
        }

        if ( response != null )
        {
            return response;
        }

        ArtifactStore store = null;
        try
        {
            store = objectMapper.readValue( json, st.getStoreClass() );
        }
        catch ( final IOException e )
        {
            final String message = "Failed to parse " + st.getStoreClass()
                                                         .getSimpleName() + " from request body.";

            logger.error( message, e );
            response = formatResponse( e, message );
        }

        if ( response != null )
        {
            return response;
        }

        logger.info( "\n\nGot artifact store: {}\n\n", store );

        try
        {
            String user = securityManager.getUser( securityContext, request );

            if ( adminController.store( store, user, false ) )
            {
                final URI uri = uriInfo.getBaseUriBuilder()
                                       .path( "/api/admin/stores" )
                                       .path( store.getPackageType() )
                                       .path( store.getType().singularEndpointName() )
                                       .build( store.getName() );

                response = formatCreatedResponseWithJsonEntity( uri, store, objectMapper );
            }
            else
            {
                response = status( CONFLICT )
                                   .entity( "{\"error\": \"Store already exists.\"}" )
                                   .type( application_json )
                                   .build();
            }
        }
        catch ( final IndyWorkflowException e )
        {
            logger.error( e.getMessage(), e );
            response = formatResponse( e );
        }
        return response;
    }

    /*
     * (non-Javadoc)
     * @see org.commonjava.indy.core.rest.admin.DeployPointAdminResource#store(java.lang.String)
     */
    @ApiOperation( "Update an existing store" )
    @ApiResponses( { @ApiResponse( code = 200, message = "The store was updated" ),
        @ApiResponse( code = 400, message = "The store specified in the body JSON didn't match the URL parameters" ), } )
    @ApiImplicitParams( { @ApiImplicitParam( allowMultiple = false, paramType = "body", name = "body", required = true, dataType = "org.commonjava.indy.model.core.ArtifactStore", value = "The artifact store definition JSON" ) } )
    @Path( "/{name}" )
    @PUT
    @Consumes( ApplicationContent.application_json )
    public Response store( final @PathParam( "packageType" ) String packageType,
                           final @ApiParam( allowableValues = "hosted,group,remote", required = true ) @PathParam( "type" ) String type,
                           final @ApiParam( required = true ) @PathParam( "name" ) String name,
                           final @Context HttpServletRequest request,
                           final @Context SecurityContext securityContext )
    {
        final StoreType st = StoreType.get( type );

        Response response = null;
        String json = null;
        try
        {
            json = IOUtils.toString( request.getInputStream() );
            json = objectMapper.patchLegacyStoreJson( json );
        }
        catch ( final IOException e )
        {
            final String message = "Failed to read " + st.getStoreClass()
                                                         .getSimpleName() + " from request body.";

            logger.error( message, e );
            response = formatResponse( e, message );
        }

        if ( response != null )
        {
            return response;
        }

        ArtifactStore store = null;
        try
        {
            store = objectMapper.readValue( json, st.getStoreClass() );
        }
        catch ( final IOException e )
        {
            final String message = "Failed to parse " + st.getStoreClass()
                                                          .getSimpleName() + " from request body.";

            logger.error( message, e );
            response = formatResponse( e, message );
        }

        if ( response != null )
        {
            return response;
        }

        if ( !packageType.equals(store.getPackageType()) || st != store.getType() || !name.equals( store.getName() ) )
        {
            response = Response.status( Status.BAD_REQUEST )
                               .entity( String.format( "Store in URL path is: '%s' but in JSON it is: '%s'",
                                                       new StoreKey( packageType, st, name ), store.getKey() ) )
                               .build();
        }

        try
        {
            String user = securityManager.getUser( securityContext, request );

            logger.info( "Storing: {}", store );
            if ( adminController.store( store, user, false ) )
            {
                response = ok().build();
            }
            else
            {
                logger.warn( "{} NOT modified!", store );
                response = notModified().build();
            }
        }
        catch ( final IndyWorkflowException e )
        {
            logger.error( e.getMessage(), e );
            response = formatResponse( e );
        }

        return response;
    }

    @ApiOperation( "Retrieve the definitions of all artifact stores of a given type on the system" )
    @ApiResponses(
            { @ApiResponse( code = 200, response = StoreListingDTO.class, message = "The store definitions" ), } )
    @GET
    @Produces( ApplicationContent.application_json )
    public Response getAll( final @ApiParam(
            "Filter only stores that support the package type (eg. maven, npm). NOTE: '_all' returns all." )
                            @PathParam( "packageType" ) String packageType,
                            final @ApiParam( allowableValues = "hosted,group,remote", required = true )
                            @PathParam( "type" ) String type )
    {

        final StoreType st = StoreType.get( type );

        Response response;
        try
        {
            final List<ArtifactStore> stores = adminController.getAllOfType( packageType, st );

            logger.info( "Returning listing containing stores:\n\t{}", new JoinString( "\n\t", stores ) );

            final StoreListingDTO<ArtifactStore> dto = new StoreListingDTO<>( stores );

            response = formatOkResponseWithJsonEntity( dto, objectMapper );
        }
        catch ( final IndyWorkflowException e )
        {
            logger.error( e.getMessage(), e );
            response = formatResponse( e );
        }

        return response;
    }

    @ApiOperation( "Retrieve the definition of a specific artifact store" )
    @ApiResponses( { @ApiResponse( code = 200, response = ArtifactStore.class, message = "The store definition" ),
        @ApiResponse( code = 404, message = "The store doesn't exist" ), } )
    @Path( "/{name}" )
    @GET
    @Produces( ApplicationContent.application_json )
    public Response get( final @PathParam( "packageType" ) String packageType,
                         final @ApiParam( allowableValues = "hosted,group,remote", required = true ) @PathParam( "type" ) String type,
                         final @ApiParam( required = true ) @PathParam( "name" ) String name )
    {
        final StoreType st = StoreType.get( type );
        final StoreKey key = new StoreKey( packageType, st, name );

        Response response;
        try
        {
            final ArtifactStore store = adminController.get( key );
            logger.info( "Returning repository: {}", store );

            if ( store == null )
            {
                response = Response.status( Status.NOT_FOUND )
                                   .build();
            }
            else
            {
                response = formatOkResponseWithJsonEntity( store, objectMapper );
            }
        }
        catch ( final IndyWorkflowException e )
        {
            logger.error( e.getMessage(), e );
            response = formatResponse( e );
        }
        return response;
    }

    @ApiOperation( "Delete an artifact store" )
    @ApiResponses( { @ApiResponse( code = 204, response = ArtifactStore.class, message = "The store was deleted (or didn't exist in the first place)" ), } )
    @Path( "/{name}" )
    @DELETE
    public Response delete( final @PathParam( "packageType" ) String packageType,
                            final @ApiParam( allowableValues = "hosted,group,remote", required = true ) @PathParam( "type" ) String type,
                            final @ApiParam( required = true ) @PathParam( "name" ) String name,
                            @Context final HttpServletRequest request,
                            final @Context SecurityContext securityContext )
    {
        final StoreType st = StoreType.get( type );
        final StoreKey key = new StoreKey( packageType, st, name );

        logger.info( "Deleting: {}", key );
        Response response;
        try
        {
            String summary = null;
            try
            {
                summary = IOUtils.toString( request.getInputStream() );
            }
            catch ( final IOException e )
            {
                // no problem, try to get the summary from a header instead.
                logger.info( "store-deletion change summary not in request body, checking headers." );
            }

            if ( isEmpty( summary ) )
            {
                summary = request.getHeader( METADATA_CHANGELOG );
            }

            if ( isEmpty( summary ) )
            {
                summary = "Changelog not provided";
            }

            String user = securityManager.getUser( securityContext, request );

            adminController.delete( key, user, summary );

            response = noContent().build();
        }
        catch ( final IndyWorkflowException e )
        {
            logger.error( e.getMessage(), e );
            response = formatResponse( e );
        }
        return response;
    }

    @ApiOperation( "Retrieve the definition of a remote by specific url" )
    @ApiResponses( { @ApiResponse( code = 200, response = ArtifactStore.class, message = "The store definition" ),
                           @ApiResponse( code = 404, message = "The remote repository doesn't exist" ), } )
    @Path( "/query/byUrl" )
    @GET
    public Response getRemoteByUrl( final @PathParam( "packageType" ) String packageType,
                                    final @ApiParam( allowableValues = "remote", required = true )
                                    @PathParam( "type" ) String type, final @QueryParam( "url" ) String url,
                                    @Context final HttpServletRequest request,
                                    final @Context SecurityContext securityContext )
    {
        if ( !"remote".equals( type ) )
        {
            return ResponseUtils.formatBadRequestResponse(
                    String.format( "Not supporte repository type of %s", type ) );
        }

        logger.info( "Get remote repository by url: {}", url );
        Response response;
        try
        {
            final List<RemoteRepository> remotes = adminController.getRemoteByUrl( url, packageType );
            logger.info( "According to url {}, Returning remote listing remote repositories: {}", url, remotes );

            if ( remotes == null || remotes.isEmpty() )
            {
                response = Response.status( Status.NOT_FOUND ).build();
            }
            else
            {
                final StoreListingDTO<RemoteRepository> dto = new StoreListingDTO<>( remotes );
                response = formatOkResponseWithJsonEntity( dto, objectMapper );
            }
        }
        catch ( final IndyWorkflowException e )
        {
            logger.error( e.getMessage(), e );
            response = formatResponse( e );
        }
        return response;
    }

}
