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
package org.commonjava.indy.httprox.handler;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.ClassUtils;
import org.apache.http.HttpRequest;
import org.apache.http.RequestLine;
import org.commonjava.indy.IndyWorkflowException;
import org.commonjava.indy.audit.ChangeSummary;
import org.commonjava.indy.bind.jaxrs.MDCManager;
import org.commonjava.indy.core.ctl.ContentController;
import org.commonjava.indy.data.ArtifactStoreQuery;
import org.commonjava.indy.data.IndyDataException;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.indy.folo.ctl.FoloConstants;
import org.commonjava.indy.folo.model.TrackingKey;
import org.commonjava.indy.httprox.conf.HttproxConfig;
import org.commonjava.indy.httprox.conf.TrackingType;
import org.commonjava.indy.httprox.keycloak.KeycloakProxyAuthenticator;
import org.commonjava.indy.httprox.util.HttpConduitWrapper;
import org.commonjava.indy.metrics.conf.IndyMetricsConfig;
import org.commonjava.indy.model.core.AccessChannel;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.HostedRepository;
import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.subsys.http.HttpWrapper;
import org.commonjava.indy.subsys.http.util.UserPass;
import org.commonjava.indy.subsys.infinispan.CacheHandle;
import org.commonjava.indy.subsys.infinispan.CacheProducer;
import org.commonjava.indy.util.ApplicationHeader;
import org.commonjava.indy.util.ApplicationStatus;
import org.commonjava.indy.util.UrlInfo;
import org.commonjava.maven.galley.TransferException;
import org.commonjava.maven.galley.event.EventMetadata;
import org.commonjava.maven.galley.model.Transfer;
import org.commonjava.maven.galley.spi.cache.CacheProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.ChannelListener;
import org.xnio.StreamConnection;
import org.xnio.conduits.ConduitStreamSinkChannel;
import org.xnio.conduits.ConduitStreamSourceChannel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URL;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.codahale.metrics.MetricRegistry.name;
import static org.apache.commons.codec.digest.DigestUtils.sha256Hex;
import static org.commonjava.indy.httprox.util.HttpProxyConstants.ALLOW_HEADER_VALUE;
import static org.commonjava.indy.httprox.util.HttpProxyConstants.CONNECT_METHOD;
import static org.commonjava.indy.httprox.util.HttpProxyConstants.GET_METHOD;
import static org.commonjava.indy.httprox.util.HttpProxyConstants.HEAD_METHOD;
import static org.commonjava.indy.httprox.util.HttpProxyConstants.OPTIONS_METHOD;
import static org.commonjava.indy.httprox.util.HttpProxyConstants.PROXY_AUTHENTICATE_FORMAT;
import static org.commonjava.indy.model.core.ArtifactStore.TRACKING_ID;
import static org.commonjava.indy.model.core.GenericPackageTypeDescriptor.GENERIC_PKG_KEY;
import static org.commonjava.indy.util.ApplicationHeader.proxy_authenticate;
import static org.commonjava.indy.util.ApplicationStatus.PROXY_AUTHENTICATION_REQUIRED;
import static org.commonjava.maven.galley.io.SpecialPathConstants.PKG_TYPE_GENERIC_HTTP;

public final class ProxyResponseWriter
                implements ChannelListener<ConduitStreamSinkChannel>
{

    private static final String TRACKED_USER_SUFFIX = "+tracking";

    private static final String HTTP_PROXY_AUTH_CACHE = "httproxy-auth-cache";

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    private final Logger restLogger = LoggerFactory.getLogger( "org.commonjava.topic.httprox.inbound" );

    private final ConduitStreamSourceChannel sourceChannel;

    private ProxyRequestReader proxyRequestReader;

    private final SocketAddress peerAddress;

    private ProxySSLTunnel sslTunnel;

    private boolean directed = false;

    private final CacheHandle<String, Boolean> proxyAuthCache;

    private Throwable error;

    private final HttproxConfig config;

    private final ContentController contentController;

    private final StoreDataManager storeManager;

    private KeycloakProxyAuthenticator proxyAuthenticator;

    private CacheProvider cacheProvider;

    private ProxyRepositoryCreator repoCreator;

    private boolean transferred;

    private HttpRequest httpRequest;

    private final MDCManager mdcManager;

    private final MetricRegistry metricRegistry;

    private final IndyMetricsConfig metricsConfig;

    private final String cls; // short class name for metrics

    public ProxyResponseWriter( final HttproxConfig config, final StoreDataManager storeManager,
                                final ContentController contentController,
                                final KeycloakProxyAuthenticator proxyAuthenticator, final CacheProvider cacheProvider,
                                final MDCManager mdcManager, final ProxyRepositoryCreator repoCreator, final StreamConnection accepted,
                                final IndyMetricsConfig metricsConfig, final MetricRegistry metricRegistry, final CacheProducer cacheProducer )
    {
        this.config = config;
        this.contentController = contentController;
        this.storeManager = storeManager;
        this.proxyAuthenticator = proxyAuthenticator;
        this.cacheProvider = cacheProvider;
        this.mdcManager = mdcManager;
        this.repoCreator = repoCreator;
        this.peerAddress = accepted.getPeerAddress();
        this.sourceChannel = accepted.getSourceChannel();
        this.metricsConfig = metricsConfig;
        this.metricRegistry = metricRegistry;
        this.cls = ClassUtils.getAbbreviatedName( getClass().getName(), 1 ); // e.g., foo.bar.ClassA -> f.b.ClassA
        this.proxyAuthCache = cacheProducer.getCache( HTTP_PROXY_AUTH_CACHE );
    }

    public void setProxyRequestReader( ProxyRequestReader proxyRequestReader )
    {
        this.proxyRequestReader = proxyRequestReader;
    }

    @Override
    public void handleEvent( final ConduitStreamSinkChannel channel )
    {
        if ( metricsConfig == null || metricRegistry == null )
        {
            doHandleEvent( channel );
            return;
        }

        Timer timer = metricRegistry.timer( name( metricsConfig.getNodePrefix(), cls, "handleEvent" ) );
        Timer.Context timerContext = timer.time();
        try
        {
            doHandleEvent( channel );
        }
        finally
        {
            timerContext.stop();
        }
    }

    private void doHandleEvent( final ConduitStreamSinkChannel sinkChannel )
    {
        if ( directed )
        {
            return;
        }

        HttpConduitWrapper http = new HttpConduitWrapper( sinkChannel, httpRequest, contentController, cacheProvider );
        if ( httpRequest == null )
        {
            if ( error != null )
            {
                logger.debug( "Handling error from request reader: " + error.getMessage(), error );
                handleError( error, http );
            }
            else
            {
                logger.debug( "Invalid state (no error or request) from request reader. Sending 400." );
                try
                {
                    http.writeStatus( ApplicationStatus.BAD_REQUEST );
                }
                catch ( final IOException e )
                {
                    logger.error( "Failed to write BAD REQUEST for missing HTTP first-line to response channel.", e );
                }
            }

            return;
        }

        restLogger.info( "START {} (from: {})", httpRequest.getRequestLine(), peerAddress );

        // TODO: Can we handle this?
        final String oldThreadName = Thread.currentThread().getName();
        Thread.currentThread().setName( "PROXY-" + httpRequest.getRequestLine().toString() );
        sinkChannel.getCloseSetter().set( ( c ) ->
        {
            restLogger.info( "END {} (from: {})", httpRequest.getRequestLine(), peerAddress );
            logger.trace("Sink channel closing.");
            Thread.currentThread().setName( oldThreadName );
            if ( sslTunnel != null )
            {
                logger.trace("Close ssl tunnel");
                sslTunnel.close();
            }
        } );

        logger.debug( "\n\n\n>>>>>>> Handle write\n\n\n" );
        if ( error == null )
        {
            try
            {
                if ( repoCreator == null )
                {
                    throw new IndyDataException( "No valid instance of ProxyRepositoryCreator" );
                }

                final UserPass proxyUserPass =
                                UserPass.parse( ApplicationHeader.proxy_authorization, httpRequest, null );

                mdcManager.putExtraHeaders( httpRequest );
                if ( proxyUserPass != null )
                {
                    mdcManager.putExternalID( proxyUserPass.getUser() );
                }

                logger.debug( "Proxy UserPass: {}\nConfig secured? {}\nConfig tracking type: {}", proxyUserPass,
                              config.isSecured(), config.getTrackingType() );
                if ( proxyUserPass == null && ( config.isSecured()
                                || TrackingType.ALWAYS == config.getTrackingType() ) )
                {

                    String realmInfo = String.format( PROXY_AUTHENTICATE_FORMAT, config.getProxyRealm() );

                    logger.info( "Not authenticated to proxy. Sending response: {} / {}: {}",
                                 PROXY_AUTHENTICATION_REQUIRED, proxy_authenticate, realmInfo );

                    http.writeStatus( PROXY_AUTHENTICATION_REQUIRED );
                    http.writeHeader( proxy_authenticate,
                                      realmInfo );
                }
                else
                {
                    RequestLine requestLine = httpRequest.getRequestLine();
                    String method = requestLine.getMethod().toUpperCase();
                    String trackingId = null;
                    boolean authenticated = true;

                    if ( proxyUserPass != null )
                    {
                        TrackingKey trackingKey = getTrackingKey( proxyUserPass );
                        if ( trackingKey != null )
                        {
                            trackingId = trackingKey.getId();
                        }

                        String authCacheKey = generateAuthCacheKey( proxyUserPass );
                        Boolean isAuthToken = proxyAuthCache.get( authCacheKey );
                        if ( Boolean.TRUE.equals( isAuthToken ) )
                        {
                            authenticated = true;
                            logger.debug("Found auth key in cache" );
                        }
                        else
                        {
                            logger.debug( "Passing BASIC authentication credentials to Keycloak bearer-token translation authenticator" );
                            authenticated = proxyAuthenticator.authenticate( proxyUserPass, http );
                            if ( authenticated )
                            {
                                proxyAuthCache.put( authCacheKey, Boolean.TRUE, config.getAuthCacheExpirationHours(), TimeUnit.HOURS );
                            }
                        }
                        logger.debug( "Authentication done, result: {}", authenticated );
                    }

                    if ( authenticated )
                    {
                        switch ( method )
                        {
                            case GET_METHOD:
                            case HEAD_METHOD:
                            {
                                final URL url = new URL( requestLine.getUri() );
                                logger.debug( "getArtifactStore starts, trackingId: {}, url: {}", trackingId, url );
                                ArtifactStore store = getArtifactStore( trackingId, url );
                                transfer( http, store, url.getPath(), GET_METHOD.equals( method ), proxyUserPass );
                                break;
                            }
                            case OPTIONS_METHOD:
                            {
                                http.writeStatus( ApplicationStatus.OK );
                                http.writeHeader( ApplicationHeader.allow, ALLOW_HEADER_VALUE );
                                break;
                            }
                            case CONNECT_METHOD:
                            {
                                String uri = requestLine.getUri(); // e.g, github.com:443
                                logger.debug( "Get CONNECT request, uri: {}", uri );

                                String[] toks = uri.split( ":" );
                                String host = toks[0];
                                int port = Integer.parseInt( toks[1] );

                                http.writeStatus( ApplicationStatus.OK );
                                http.writeHeader( "Status", "200 OK\n" );

                                directed = true;

                                // After this, the proxy simply opens a plain socket to the target server and relays
                                // everything between the initial client and the target server (including the TLS handshake).

                                SocketChannel socketChannel;

                                if ( !config.isMITMEnabled() )
                                {
                                    InetSocketAddress target = new InetSocketAddress( host, port );
                                    socketChannel = SocketChannel.open( target );
                                }
                                else
                                {
                                    ProxyMITMSSLServer svr = new ProxyMITMSSLServer( host, port );
                                    new Thread( svr ).start();

                                    socketChannel = svr.get();

                                    if ( socketChannel == null )
                                    {
                                        logger.debug( "Failed to get MITM socket channel" );
                                        http.writeStatus( ApplicationStatus.SERVER_ERROR );
                                        svr.stop();
                                        break;
                                    }
                                }

                                sslTunnel = new ProxySSLTunnel( sinkChannel, socketChannel );
                                sslTunnel.open();
                                proxyRequestReader.setProxySSLTunnel( sslTunnel ); // client input will be directed to target socket

                                break;
                            }
                            default:
                            {
                                http.writeStatus( ApplicationStatus.METHOD_NOT_ALLOWED );
                            }
                        }
                    }
                }

                logger.debug( "Response complete." );
            }
            catch ( final Throwable e )
            {
                error = e;
            }
            finally
            {
                mdcManager.clear();
            }
        }

        if ( error != null )
        {
            handleError( error, http );
        }

        try
        {
            if ( directed )
            {
                ; // do not close sink channel
            }
            else
            {
                http.close();
            }
        }
        catch ( final IOException e )
        {
            logger.error( "Failed to flush/shutdown response.", e );
        }
    }

    private String generateAuthCacheKey( UserPass proxyUserPass )
    {
        return sha256Hex( proxyUserPass.getUser() + ":" + proxyUserPass.getPassword() );
    }

    private void handleError( final Throwable error, final HttpWrapper http )
    {
        logger.error( "HTTProx request failed: " + error.getMessage(), error );
        try
        {
            if ( http.isOpen() )
            {
                if ( error instanceof IndyWorkflowException )
                {
                    http.writeStatus( ApplicationStatus.getStatus( ( (IndyWorkflowException) error ).getStatus() ) );
                }
                else
                {
                    http.writeStatus( ApplicationStatus.SERVER_ERROR );
                }

                http.writeError( error );

                logger.debug( "Response error complete." );
                //                    Channels.flushBlocking( channel );
                //                    channel.close();
            }
        }
        catch ( final IOException closeException )
        {
            logger.error( "Failed to close httprox request: " + error.getMessage(), error );
        }
    }

    private void transfer( final HttpConduitWrapper http, final ArtifactStore store, final String path,
                           final boolean writeBody, final UserPass proxyUserPass )
                    throws IOException, IndyWorkflowException
    {
        if ( metricsConfig == null || metricRegistry == null )
        {
            doTransfer( http, store, path, writeBody, proxyUserPass );
            return;
        }

        Timer timer = metricRegistry.timer( name( metricsConfig.getNodePrefix(), cls, "transfer" ) );
        Timer.Context timerContext = timer.time();
        try
        {
            doTransfer( http, store, path, writeBody, proxyUserPass );
        }
        finally
        {
            timerContext.stop();
        }
    }

    private void doTransfer( final HttpConduitWrapper http, final ArtifactStore store, final String path,
                           final boolean writeBody, final UserPass proxyUserPass )
                    throws IOException, IndyWorkflowException
    {
        if ( transferred )
        {
            return;
        }

        transferred = true;
        if ( !http.isOpen() )
        {
            throw new IOException( "Sink channel already closed (or null)!" );
        }

        final EventMetadata eventMetadata = createEventMetadata( writeBody, proxyUserPass, path, store );

        Transfer txfr = null;
        try
        {
            txfr = contentController.get( store.getKey(), path, eventMetadata );
        }
        catch ( final IndyWorkflowException e )
        {
            // TODO: timeouts?
            // block TransferException to allow handling below.
            if ( !( e.getCause() instanceof TransferException ) )
            {
                throw e;
            }
            logger.debug( "Suppressed exception for further handling inside proxy logic:", e );
        }

        if ( txfr != null && txfr.exists() )
        {
            http.writeExistingTransfer( txfr, writeBody, path, eventMetadata );
        }
        else
        {
            http.writeNotFoundTransfer( store, path );
        }
    }

    private EventMetadata createEventMetadata( final boolean writeBody, final UserPass proxyUserPass, final String path,
                                               final ArtifactStore store )
                    throws IndyWorkflowException
    {
        final EventMetadata eventMetadata = new EventMetadata();
        if ( writeBody )
        {
            TrackingKey tk = getTrackingKey( proxyUserPass );

            if ( tk != null )
            {
                logger.debug( "TRACKING {} in {} (KEY: {})", path, store, tk );
                eventMetadata.set( FoloConstants.TRACKING_KEY, tk );

                eventMetadata.set( FoloConstants.ACCESS_CHANNEL, AccessChannel.GENERIC_PROXY );
            }
            else
            {
                logger.debug( "NOT TRACKING: {} in {}", path, store );
            }
        }
        else
        {
            logger.debug( "NOT TRACKING non-body request: {} in {}", path, store );
        }

        eventMetadata.setPackageType( PKG_TYPE_GENERIC_HTTP );

        return eventMetadata;
    }

    private TrackingKey getTrackingKey( UserPass proxyUserPass ) throws IndyWorkflowException
    {
        TrackingKey tk = null;
        switch ( config.getTrackingType() )
        {
            case ALWAYS:
            {
                if ( proxyUserPass == null )
                {
                    throw new IndyWorkflowException( ApplicationStatus.BAD_REQUEST.code(),
                                                     "Tracking is always-on, but no username was provided! Cannot initialize tracking key." );
                }

                tk = new TrackingKey( proxyUserPass.getUser() );

                break;
            }
            case SUFFIX:
            {
                if ( proxyUserPass != null )
                {
                    final String user = proxyUserPass.getUser();

                    if ( user != null && user.endsWith( TRACKED_USER_SUFFIX ) && user.length() > TRACKED_USER_SUFFIX.length() )
                    {
                        tk = new TrackingKey( StringUtils.substring( user, 0, - TRACKED_USER_SUFFIX.length() ) );
                    }
                }

                break;
            }
            default:
            {
            }
        }
        return tk;
    }

    private ArtifactStore getArtifactStore( String trackingId, final URL url )
                    throws IndyDataException
    {
        if ( metricsConfig == null || metricRegistry == null )
        {
            return doGetArtifactStore( trackingId, url );
        }

        Timer timer = metricRegistry.timer( name( metricsConfig.getNodePrefix(), cls, "getArtifactStore" ) );
        Timer.Context timerContext = timer.time();
        try
        {
            return doGetArtifactStore( trackingId, url );
        }
        finally
        {
            timerContext.stop();
        }
    }

    private ArtifactStore doGetArtifactStore( String trackingId, final URL url )
                    throws IndyDataException
    {
        int port = getPort( url );

        if ( trackingId != null )
        {
            String groupName = repoCreator.formatId( url.getHost(), port, 0, trackingId, StoreType.group );

            ArtifactStoreQuery<Group> query =
                            storeManager.query().packageType( GENERIC_PKG_KEY ).storeType( Group.class );

            Group group = query.getGroup( groupName );
            logger.debug( "Get httproxy group, group: {}", group );

            if ( group == null )
            {
                logger.debug( "Creating repositories (group, hosted, remote) for HTTProx request: {}, trackingId: {}",
                              url, trackingId );
                ProxyCreationResult result = createRepo( trackingId, url, null );
                group = result.getGroup();
            }
            return group;
        }
        else
        {
            RemoteRepository remote;
            final String baseUrl = getBaseUrl( url, true );

            ArtifactStoreQuery<RemoteRepository> query =
                            storeManager.query().packageType( GENERIC_PKG_KEY ).storeType( RemoteRepository.class );

            remote = query.stream()
                          .filter( store -> store.getUrl().equals( baseUrl )
                                          && store.getMetadata( TRACKING_ID ) == null )
                          .findFirst()
                          .orElse( null );

            logger.debug( "Get httproxy remote, remote: {}", remote );
            if ( remote == null )
            {
                logger.debug( "Creating remote repository for HTTProx request: {}", url );
                String name = getRemoteRepositoryName( url );
                ProxyCreationResult result = createRepo( trackingId, url, name );
                remote = result.getRemote();
            }
            return remote;
        }

    }

    /**
     * Create repositories (group, remote, hosted) when trackingId is present. Otherwise create normal remote
     * repository with specified name.
     *
     * @param trackingId
     * @param url
     * @param name distinct remote repository name. null if trackingId is given
     */
    private ProxyCreationResult createRepo( String trackingId, URL url, String name )
                    throws IndyDataException
    {
        UrlInfo info = new UrlInfo( url.toExternalForm() );

        UserPass up = UserPass.parse( ApplicationHeader.authorization, httpRequest, url.getAuthority() );
        String baseUrl = getBaseUrl( url, false );

        logger.debug( ">>>> Create repo: trackingId=" + trackingId + ", name=" + name );
        ProxyCreationResult result = repoCreator.create( trackingId, name, baseUrl, info, up,
                                                         LoggerFactory.getLogger( repoCreator.getClass() ) );
        ChangeSummary changeSummary =
                        new ChangeSummary( ChangeSummary.SYSTEM_USER, "Creating HTTProx proxy for: " + info.getUrl() );

        RemoteRepository remote = result.getRemote();
        if ( remote != null )
        {
            storeManager.storeArtifactStore( remote, changeSummary, false, true, new EventMetadata() );
        }

        HostedRepository hosted = result.getHosted();
        if ( hosted != null )
        {
            storeManager.storeArtifactStore( hosted, changeSummary, false, true, new EventMetadata() );
        }

        Group group = result.getGroup();
        if ( group != null )
        {
            storeManager.storeArtifactStore( group, changeSummary, false, true, new EventMetadata() );
        }

        return result;
    }

    /**
     * if repo with this name already exists, we need to use a different name
     */
    private String getRemoteRepositoryName( URL url ) throws IndyDataException
    {
        final String name = repoCreator.formatId( url.getHost(), getPort( url ), 0, null, StoreType.remote );

        logger.debug( "Looking for remote repo starts with name: {}", name );

        AbstractProxyRepositoryCreator abstractProxyRepositoryCreator = null;
        if ( repoCreator instanceof AbstractProxyRepositoryCreator )
        {
            abstractProxyRepositoryCreator = (AbstractProxyRepositoryCreator) repoCreator;
        }

        if ( abstractProxyRepositoryCreator == null )
        {
            return name;
        }

        Predicate<ArtifactStore> filter = abstractProxyRepositoryCreator.getNameFilter( name );
        List<String> l = storeManager.query()
                                     .packageType( GENERIC_PKG_KEY )
                                     .storeType( RemoteRepository.class )
                                     .stream( filter )
                                     .map( repository -> repository.getName() )
                                     .collect( Collectors.toList() );

        if ( l.isEmpty() )
        {
            return name;
        }
        return abstractProxyRepositoryCreator.getNextName( l );
    }

    private int getPort( URL url )
    {
        int port = url.getPort();
        if ( port < 1 )
        {
            port = url.getDefaultPort();
        }
        return port;
    }

    private String getBaseUrl( URL url, boolean includeDefaultPort )
    {
        int port = getPort( url );
        String portStr = !includeDefaultPort && port == url.getDefaultPort() ? "" : ":" + Integer.toString( url.getPort() );
        return String.format( "%s://%s%s/", url.getProtocol(), url.getHost(), portStr );
    }

    public void setError( final Throwable error )
    {
        this.error = error;
    }

    public void setHttpRequest( final HttpRequest request )
    {
        this.httpRequest = request;
    }
}
