/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package me.surli.ldap;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Set;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.ldap.Control;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;

import org.apache.commons.io.FileUtils;
import org.apache.directory.server.configuration.MutableServerStartupConfiguration;
import org.apache.directory.server.core.configuration.MutablePartitionConfiguration;
import org.apache.directory.server.core.configuration.ShutdownConfiguration;
import org.apache.directory.server.jndi.ServerContextFactory;
import org.apache.directory.server.unit.AbstractServerTest;
import org.apache.directory.shared.ldap.exception.LdapConfigurationException;
import org.apache.directory.shared.ldap.ldif.Entry;
import org.apache.directory.shared.ldap.ldif.LdifReader;
import org.apache.directory.shared.ldap.name.LdapDN;
import org.apache.mina.util.AvailablePortFinder;

/**
 * Tool to start and stop embedded LDAP server.
 * Code inspired from https://github.com/xwiki-contrib/ldap/blob/master/ldap-test/ldap-test-tests/src/test/it/org/xwiki/contrib/ldap/framework/LDAPRunner.java
 */
public class LDAPRunner
{
    protected LdapContext sysRoot;
    protected LdapContext rootDSE;
    protected boolean doDelete = true;
    protected MutableServerStartupConfiguration configuration = new MutableServerStartupConfiguration();
    protected int port = -1;

    /**
     * Start the server.
     */
    public void start() throws Exception
    {
        // Add partition 'sevenSeas'
        MutablePartitionConfiguration pcfg = new MutablePartitionConfiguration();
        pcfg.setName("sevenSeas");
        pcfg.setSuffix("o=sevenseas");

        // Create some indices
        Set<String> indexedAttrs = new HashSet<String>();
        indexedAttrs.add("objectClass");
        indexedAttrs.add("o");
        pcfg.setIndexedAttributes(indexedAttrs);

        // Create a first entry associated to the partition
        Attributes attrs = new BasicAttributes(true);

        // First, the objectClass attribute
        Attribute attr = new BasicAttribute("objectClass");
        attr.add("top");
        attr.add("organization");
        attrs.put(attr);

        // The the 'Organization' attribute
        attr = new BasicAttribute("o");
        attr.add("sevenseas");
        attrs.put(attr);

        // Associate this entry to the partition
        pcfg.setContextEntry(attrs);

        // As we can create more than one partition, we must store
        // each created partition in a Set before initialization
        Set<MutablePartitionConfiguration> pcfgs = new HashSet<MutablePartitionConfiguration>();
        pcfgs.add(pcfg);

        configuration.setContextPartitionConfigurations(pcfgs);

        // Create a working directory
        File workingDirectory = new File("server-work");
        configuration.setWorkingDirectory(workingDirectory);

        // Now, let's call the upper class which is responsible for the
        // partitions creation
        this.doDelete(this.configuration.getWorkingDirectory());
        this.port = AvailablePortFinder.getNextAvailable(1024);
        this.configuration.setLdapPort(this.port);
        this.configuration.setShutdownHookEnabled(false);
        this.setContexts("uid=admin,ou=system", "secret");

        System.out.println("LDAP server started on port [" + port + "]");

        System.setProperty(LDAPTestSetup.SYSPROPNAME_LDAPPORT, "" + port);

        // Load a demo ldif file
        importLdif(this.getClass().getResourceAsStream("/init.ldif"));
    }

    protected void doDelete(File wkdir) throws IOException
    {
        if (this.doDelete) {
            if (wkdir.exists()) {
                FileUtils.deleteDirectory(wkdir);
            }

            if (wkdir.exists()) {
                throw new IOException("Failed to delete: " + wkdir);
            }
        }
    }

    protected void setContexts(String user, String passwd) throws NamingException
    {
        Hashtable env = new Hashtable(this.configuration.toJndiEnvironment());
        env.put("java.naming.security.principal", user);
        env.put("java.naming.security.credentials", passwd);
        env.put("java.naming.security.authentication", "simple");
        env.put("java.naming.factory.initial", ServerContextFactory.class.getName());
        this.setContexts(env);
    }

    protected void setContexts(Hashtable env) throws NamingException {
        Hashtable envFinal = new Hashtable(env);
        envFinal.put("java.naming.provider.url", "ou=system");
        this.sysRoot = new InitialLdapContext(envFinal, (Control[])null);
        envFinal.put("java.naming.provider.url", "");
        this.rootDSE = new InitialLdapContext(envFinal, (Control[])null);
    }

    protected void importLdif(InputStream in) throws NamingException {
        try {
            LdifReader iterator = new LdifReader(in);

            while(iterator.hasNext()) {
                Entry entry = (Entry)iterator.next();
                LdapDN dn = new LdapDN(entry.getDn());
                this.rootDSE.createSubcontext(dn, entry.getAttributes());
            }

        } catch (Exception var5) {
            String msg = "failed while trying to parse system ldif file";
            NamingException ne = new LdapConfigurationException(msg);
            ne.setRootCause(var5);
            throw ne;
        }
    }

    /**
     * Shutdown the server.
     */
    public void stop() throws Exception
    {
        Hashtable env = new Hashtable();
        env.put("java.naming.provider.url", "ou=system");
        env.put("java.naming.factory.initial", "org.apache.directory.server.jndi.ServerContextFactory");
        env.putAll((new ShutdownConfiguration()).toJndiEnvironment());
        env.put("java.naming.security.principal", "uid=admin,ou=system");
        env.put("java.naming.security.credentials", "secret");

        try {
            new InitialContext(env);
        } catch (Exception var3) {
        }

        this.sysRoot = null;
        this.doDelete(this.configuration.getWorkingDirectory());
        this.configuration = new MutableServerStartupConfiguration();

        System.clearProperty(LDAPTestSetup.SYSPROPNAME_LDAPPORT);
    }
}
