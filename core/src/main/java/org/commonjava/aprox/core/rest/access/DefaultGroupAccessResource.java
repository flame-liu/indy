/*******************************************************************************
 * Copyright (C) 2014 John Casey.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package org.commonjava.aprox.core.rest.access;

import static org.commonjava.aprox.util.LocationUtils.getKey;

import java.io.IOException;

import javax.activation.MimetypesFileTypeMap;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;

import org.commonjava.aprox.data.ProxyDataException;
import org.commonjava.aprox.data.StoreDataManager;
import org.commonjava.aprox.model.DeployPoint;
import org.commonjava.aprox.model.StoreKey;
import org.commonjava.aprox.rest.AproxWorkflowException;
import org.commonjava.aprox.rest.access.GroupAccessResource;
import org.commonjava.aprox.rest.util.GroupContentManager;
import org.commonjava.maven.galley.model.Transfer;
import org.commonjava.util.logging.Logger;

import com.wordnik.swagger.annotations.Api;
import com.wordnik.swagger.annotations.ApiError;
import com.wordnik.swagger.annotations.ApiOperation;
import com.wordnik.swagger.annotations.ApiParam;

@Path( "/group" )
@Api( description = "Handles GET/PUT/DELETE requests for content in the constituency of group store", value = "Handle group content" )
@RequestScoped
public class DefaultGroupAccessResource
    implements GroupAccessResource
{
    private final Logger logger = new Logger( getClass() );

    @Inject
    private GroupContentManager groupContentManager;

    @Inject
    private StoreDataManager dataManager;

    @Context
    private UriInfo uriInfo;

    @DELETE
    @ApiOperation( value = "Delete content at the given path from all constituent stores within the group with the given name." )
    @ApiError( code = 404, reason = "If the deletion fails" )
    @Path( "/{name}{path: (/.+)?}" )
    public Response deleteContent( @ApiParam( "Name of the store" ) @PathParam( "name" ) final String name,
                                   @ApiParam( "Content path within the store" ) @PathParam( "path" ) final String path )
    {
        try
        {
            if ( groupContentManager.delete( name, path ) )
            {
                return Response.ok()
                               .build();
            }
            else
            {
                return Response.status( Status.NOT_FOUND )
                               .build();
            }

        }
        catch ( final AproxWorkflowException e )
        {
            logger.error( e.getMessage(), e );
            return e.getResponse();
        }
        catch ( final IOException e )
        {
            logger.error( e.getMessage(), e );
            return Response.serverError()
                           .build();
        }
    }

    /*
     * (non-Javadoc)
     * @see org.commonjava.aprox.core.rest.access.GroupAccessResource#getProxyContent(java.lang.String,
     * java.lang.String)
     */
    @Override
    @GET
    @ApiOperation( value = "Retrieve content from the FIRST constituent store that contains the given path, within the group with the given name." )
    @ApiError( code = 404, reason = "If none of the constituent stores contains the path" )
    @Path( "/{name}{path: (/.+)?}" )
    public Response getProxyContent( @ApiParam( "Name of the store" ) @PathParam( "name" ) final String name,
                                     @ApiParam( "Content path within the store" ) @PathParam( "path" ) final String path )
    {
        try
        {
            final Transfer item = groupContentManager.retrieve( name, path );

            if ( item == null )
            {
                return Response.status( Status.NOT_FOUND )
                               .build();
            }

            final String mimeType = new MimetypesFileTypeMap().getContentType( item.getPath() );

            return Response.ok( item.openInputStream(), mimeType )
                           .build();

        }
        catch ( final AproxWorkflowException e )
        {
            logger.error( e.getMessage(), e );
            return e.getResponse();
        }
        catch ( final IOException e )
        {
            logger.error( e.getMessage(), e );
            return Response.serverError()
                           .build();
        }
    }

    /*
     * (non-Javadoc)
     * @see org.commonjava.aprox.core.rest.access.GroupAccessResource#createContent(java.lang.String, java.lang.String,
     * javax.servlet.http.HttpServletRequest)
     */
    @Override
    @PUT
    @ApiOperation( value = "Store new content at the given path in the first deploy-point store constituent listed in the group with the given name." )
    @ApiError( code = 404, reason = "If the group doesn't contain any deploy-point stores" )
    @Path( "/{name}/{path: (.+)}" )
    public Response createContent( @ApiParam( "Name of the store" ) @PathParam( "name" ) final String name,
                                   @ApiParam( "Content path within the store" ) @PathParam( "path" ) final String path,
                                   @Context final HttpServletRequest request )
    {
        try
        {
            final Transfer item = groupContentManager.store( name, path, request.getInputStream() );
            final StoreKey key = getKey( item );

            DeployPoint deploy;
            try
            {
                deploy = dataManager.getDeployPoint( key.getName() );
            }
            catch ( final ProxyDataException e )
            {
                logger.error( e.getMessage(), e );
                return Response.serverError()
                               .build();
            }

            if ( deploy == null )
            {
                return Response.status( Status.NOT_FOUND )
                               .build();
            }

            return Response.created( uriInfo.getAbsolutePathBuilder()
                                            .path( deploy.getName() )
                                            .path( path )
                                            .build() )
                           .build();
        }
        catch ( final AproxWorkflowException e )
        {
            logger.error( e.getMessage(), e );
            return e.getResponse();
        }
        catch ( final IOException e )
        {
            logger.error( "Failed to open stream from request: %s", e, e.getMessage() );
            return Response.serverError()
                           .build();
        }
    }
}
