package org.commonjava.aprox.autoprox;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Default;
import javax.inject.Named;

import org.commonjava.aprox.dto.UIRoute;
import org.commonjava.aprox.dto.UISection;
import org.commonjava.aprox.spi.AproxAddOn;
import org.commonjava.aprox.spi.AproxAddOnID;

@ApplicationScoped
@Default
@Named( "autoprox" )
public class AutoProxAddOn
    implements AproxAddOn
{

    private static final String ROUTE_CALC = "/autoprox/calc";

    private static final String ROUTE_CALC_PREFILL = "/autoprox/calc/:type/:name";

    private static final String ROUTE_RULES = "/autoprox/rules";

    private AproxAddOnID autoproxId;

    @Override
    public AproxAddOnID getId()
    {
        if ( autoproxId == null )
        {
            autoproxId =
                new AproxAddOnID().withName( "AutoProx" )
                                  .withInitJavascriptHref( "autoprox/js/autoprox.js" )
                                  .withRoute( new UIRoute().withRoute( ROUTE_CALC )
                                                           .withTemplateHref( "autoprox/partials/calc.html" ) )
                                  .withRoute( new UIRoute().withRoute( ROUTE_CALC_PREFILL )
                                                           .withTemplateHref( "autoprox/partials/calc.html" ) )
                                  .withRoute( new UIRoute().withRoute( ROUTE_RULES )
                                                           .withTemplateHref( "autoprox/partials/rules.html" ) )
                                  .withSection( new UISection().withName( "AutoProx Calculator" )
                                                               .withRoute( ROUTE_CALC ) )
                                  .withSection( new UISection().withName( "AutoProx Rules" )
                                                               .withRoute( ROUTE_RULES ) );
        }

        return autoproxId;
    }
}