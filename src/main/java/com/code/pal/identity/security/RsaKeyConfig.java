package com.code.pal.identity.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.converter.RsaKeyConverters;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * Loads the RSA key pair used to sign/verify JWTs (RS256).
 *
 * <p>Only this module signs with the private key; any future service verifies with the
 * public key alone — so splitting out a service never requires sharing a secret.
 */
@Configuration
public class RsaKeyConfig {

    @Bean
    public RSAPrivateKey jwtPrivateKey(
            @Value("${jwt.rsa.private-key}") String privateKeyLocation,
            ResourceLoader resourceLoader) throws IOException {
        try (InputStream in = resolve(privateKeyLocation, "jwt-private.pem", resourceLoader).getInputStream()) {
            return RsaKeyConverters.pkcs8().convert(in);
        }
    }

    @Bean
    public RSAPublicKey jwtPublicKey(
            @Value("${jwt.rsa.public-key}") String publicKeyLocation,
            ResourceLoader resourceLoader) throws IOException {
        try (InputStream in = resolve(publicKeyLocation, "jwt-public.pem", resourceLoader).getInputStream()) {
            return RsaKeyConverters.x509().convert(in);
        }
    }

    private static Resource resolve(String location, String localFileName, ResourceLoader resourceLoader) throws IOException {
        if (!location.startsWith("local:")) return resourceLoader.getResource(location);

        // IntelliJ may use either the repository root or the nested backend directory as user.dir.
        // Keep generated keys out of the classpath/source tree while allowing both local launch paths.
        for (Path candidate : new Path[]{
                Path.of(".secrets", localFileName),
                Path.of("Code-Pal", ".secrets", localFileName)}) {
            Path absolute = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(absolute)) return new FileSystemResource(absolute);
        }
        throw new FileNotFoundException("Local JWT key not found. Expected .secrets/" + localFileName
                + " or Code-Pal/.secrets/" + localFileName + ". Set JWT_PRIVATE_KEY/JWT_PUBLIC_KEY for another location.");
    }
}
