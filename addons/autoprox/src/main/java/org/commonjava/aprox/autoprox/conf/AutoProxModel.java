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
package org.commonjava.aprox.autoprox.conf;

import javax.enterprise.inject.Alternative;
import javax.inject.Named;

import org.commonjava.aprox.model.DeployPoint;
import org.commonjava.aprox.model.Group;
import org.commonjava.aprox.model.Repository;

@javax.enterprise.context.ApplicationScoped
@Alternative
@Named( "dont-use-directly" )
public class AutoProxModel
{

    private Repository repo;

    private DeployPoint deploy;

    private Group group;

    private String repoValidationPath;

    public Repository getRepo()
    {
        return repo;
    }

    public DeployPoint getDeploy()
    {
        return deploy;
    }

    public Group getGroup()
    {
        return group;
    }

    public void setRepo( final Repository repo )
    {
        this.repo = repo;
    }

    public void setDeploy( final DeployPoint deploy )
    {
        this.deploy = deploy;
    }

    public void setGroup( final Group group )
    {
        this.group = group;
    }

    public String getRepoValidationPath()
    {
        return repoValidationPath;
    }

    public void setRepoValidationPath( final String path )
    {
        this.repoValidationPath = path;
    }

}
