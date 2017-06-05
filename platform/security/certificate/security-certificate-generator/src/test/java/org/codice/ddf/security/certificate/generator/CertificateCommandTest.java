/**
 * Copyright (c) Codice Foundation
 * <p>
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. A copy of the GNU Lesser General Public License
 * is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */
package org.codice.ddf.security.certificate.generator;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;

import org.apache.commons.io.IOUtils;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNamesBuilder;
import org.bouncycastle.cert.X509CertificateHolder;
import org.hamcrest.MatcherAssert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;

import ddf.security.SecurityConstants;

@RunWith(MockitoJUnitRunner.class)
public class CertificateCommandTest {
    private static final String[] SANs = new String[] {"IP:1.2.3.4", "DNS:A"};

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    File systemKeystoreFile = null;

    private static void validateCertificateHasNoSAN(KeyStoreFile ksf, String alias)
            throws Exception {
        final KeyStore.Entry ke = ksf.getEntry(alias);

        assertThat(ke, instanceOf(KeyStore.PrivateKeyEntry.class));
        final KeyStore.PrivateKeyEntry pke = (KeyStore.PrivateKeyEntry) ke;
        final Certificate c = pke.getCertificate();
        final X509CertificateHolder holder = new X509CertificateHolder(c.getEncoded());

        MatcherAssert.assertThat("There should be no subject alternative name extension",
                holder.getExtension(org.bouncycastle.asn1.x509.Extension.subjectAlternativeName),
                nullValue(org.bouncycastle.asn1.x509.Extension.class));
    }

    private static void validateCertificateHasSANs(KeyStoreFile ksf, String alias)
            throws Exception {
        final KeyStore.Entry ke = ksf.getEntry(alias);

        assertThat(ke, instanceOf(KeyStore.PrivateKeyEntry.class));
        final KeyStore.PrivateKeyEntry pke = (KeyStore.PrivateKeyEntry) ke;
        final Certificate c = pke.getCertificate();
        final X509CertificateHolder holder = new X509CertificateHolder(c.getEncoded());
        final org.bouncycastle.asn1.x509.Extension csn =
                holder.getExtension(org.bouncycastle.asn1.x509.Extension.subjectAlternativeName);

        MatcherAssert.assertThat(csn.getParsedValue()
                        .toASN1Primitive()
                        .getEncoded(ASN1Encoding.DER),
                equalTo(new GeneralNamesBuilder().addName(new GeneralName(GeneralName.iPAddress,
                        "1.2.3.4"))
                        .addName(new GeneralName(GeneralName.dNSName, "A"))
                        .build()
                        .getEncoded(ASN1Encoding.DER)));
    }

    @Before
    public void setup() throws IOException {
        this.systemKeystoreFile = temporaryFolder.newFile("serverKeystore.jks");
        final FileOutputStream systemKeyOutStream = new FileOutputStream(systemKeystoreFile);
        final InputStream systemKeyStream = CertificateGenerator.class.getResourceAsStream(
                "/serverKeystore.jks");

        IOUtils.copy(systemKeyStream, systemKeyOutStream);
        IOUtils.closeQuietly(systemKeyOutStream);
        IOUtils.closeQuietly(systemKeyStream);

        System.setProperty(SecurityConstants.KEYSTORE_TYPE, "jks");
        System.setProperty(SecurityConstants.KEYSTORE_PATH, systemKeystoreFile.getAbsolutePath());
        System.setProperty(SecurityConstants.KEYSTORE_PASSWORD, "changeit");
    }

    @Test
    public void testConfigureDemoCertWithoutSAN() throws Exception {
        KeyStoreFile ksf = CertificateCommand.getKeyStoreFile();
        assertThat(ksf.aliases()
                .size(), is(2));
        assertThat(ksf.isKey("my-fqdn"), is(false));

        assertThat(CertificateCommand.configureDemoCert("my-fqdn", null), equalTo("CN=my-fqdn"));

        ksf = CertificateCommand.getKeyStoreFile();

        assertThat(ksf.aliases()
                .size(), is(2));
        validateCertificateHasNoSAN(ksf, "my-fqdn");
    }

    @Test
    public void testConfigureDemoCertWithSAN() throws Exception {
        KeyStoreFile ksf = CertificateCommand.getKeyStoreFile();
        assertThat(ksf.aliases()
                .size(), is(2));
        assertThat(ksf.isKey("my-fqdn"), is(false));

        assertThat(CertificateCommand.configureDemoCert("my-fqdn", CertificateCommandTest.SANs),
                equalTo("CN=my-fqdn"));

        ksf = CertificateCommand.getKeyStoreFile();

        assertThat(ksf.aliases()
                .size(), is(2));
        validateCertificateHasSANs(ksf, "my-fqdn");
    }

    //    @Test(expected = CertificateGeneratorException.class)
    //    public void testSignWithCertificateException() throws Exception {
    //        DemoCertificateAuthority demoCa = new DemoCertificateAuthority() {
    //            JcaX509CertificateConverter newCertConverter() {
    //                return mockConverter;
    //            }
    //        };
    //
    //        when(csr.newCertificateBuilder(any(X509Certificate.class))).thenReturn(mockBuilder);
    //        when(mockBuilder.build(any(ContentSigner.class))).thenThrow(CertificateException.class);
    //
    //        demoCa.sign(csr);
    //    }
    //
    //    @Test
    //    public void testSign() throws Exception {
    //
    //        DemoCertificateAuthority demoCa = new DemoCertificateAuthority() {
    //            JcaX509CertificateConverter newCertConverter() {
    //                return mockConverter;
    //            }
    //        };
    //
    //        X509Certificate mockSignedCert = demoCa.getCertificate();
    //        X509Certificate mockIssuerCert = demoCa.getCertificate();
    //
    //        when(csr.newCertificateBuilder(any(X509Certificate.class))).thenReturn(mockBuilder);
    //        when(mockBuilder.build(any(ContentSigner.class))).thenReturn(mockHolder);
    //        when(mockConverter.getCertificate(any(X509CertificateHolder.class))).thenReturn(
    //                mockSignedCert);
    //        when(csr.getSubjectPrivateKey()).thenReturn(mockPrivateKey);
    //        when(csr.getSubjectPublicKey()).thenReturn(mockPublicKey);
    //        when(mockPrivateKey.getAlgorithm()).thenReturn("RSA");
    //        when(mockPublicKey.getAlgorithm()).thenReturn("RSA");
    //
    //        KeyStore.PrivateKeyEntry newObject = demoCa.sign(csr);
    //        assertThat("Expected instance of a different class",
    //                newObject,
    //                instanceOf(KeyStore.PrivateKeyEntry.class));
    //    }
    //
    //    @Test(expected = CertificateGeneratorException.class)
    //    public void testSignWithCertIOException() throws Exception {
    //        DemoCertificateAuthority demoCa = new DemoCertificateAuthority() {
    //            JcaX509CertificateConverter newCertConverter() {
    //                return mockConverter;
    //            }
    //        };
    //
    //        when(csr.newCertificateBuilder(any(X509Certificate.class))).thenReturn(mockBuilder);
    //        when(mockBuilder.build(any(ContentSigner.class))).thenThrow(CertIOException.class);
    //
    //        demoCa.sign(csr);
    //    }
    //
    //    @Test
    //    public void testConstructor() {
    //        assertNotNull(new CertificateAuthority(mockCert, mockPrivateKey));
    //    }
    //
    //    @Test(expected = IllegalArgumentException.class)
    //    public void constructNull1() {
    //        new CertificateAuthority(null, mockPrivateKey);
    //    }
    //
    //    @Test(expected = IllegalArgumentException.class)
    //    public void constructNull2() {
    //        new CertificateAuthority(mockCert, null);
    //    }
    //
    //    @Test
    //    public void makeCertConverter() {
    //        assertThat("Could not create new certificate converter",
    //                new DemoCertificateAuthority().newCertConverter(),
    //                instanceOf(JcaX509CertificateConverter.class));
    //    }
}