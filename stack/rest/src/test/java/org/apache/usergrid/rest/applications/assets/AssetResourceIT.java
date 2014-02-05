package org.apache.usergrid.rest.applications.assets;


import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import javax.ws.rs.core.MediaType;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.io.IOUtils;
import org.apache.usergrid.cassandra.Concurrent;
import org.apache.usergrid.rest.AbstractRestIT;
import org.apache.usergrid.rest.applications.utils.UserRepo;
import org.apache.usergrid.services.assets.data.AssetUtils;

import com.sun.jersey.multipart.FormDataMultiPart;
import java.util.HashMap;

import static org.apache.usergrid.utils.MapUtils.hashMap;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;


@Concurrent()
public class AssetResourceIT extends AbstractRestIT {

    private Logger LOG = LoggerFactory.getLogger( AssetResourceIT.class );


    /** @Deprecated Tests legacy API */
    @Test
    public void verifyBinaryCrud() throws Exception {
        UserRepo.INSTANCE.load( resource(), access_token );

        UUID userId = UserRepo.INSTANCE.getByUserName( "user1" );
        Map<String, String> payload =
                hashMap( "path", "my/clean/path" ).map( "owner", userId.toString() ).map( "someprop", "somevalue" );

        JsonNode node =
                mapper.valueToTree(resource().path( "/test-organization/test-app/assets" ).queryParam( "access_token", access_token )
                        .accept( MediaType.APPLICATION_JSON ).type( MediaType.APPLICATION_JSON_TYPE )
                        .post( HashMap.class, payload ));
        JsonNode idNode = node.get( "entities" ).get( 0 ).get( "uuid" );
        UUID id = UUID.fromString( idNode.asText() );
        assertNotNull( idNode.asText() );
        logNode( node );

        byte[] data = IOUtils.toByteArray( this.getClass().getResourceAsStream( "/cassandra_eye.jpg" ) );
        resource().path( "/test-organization/test-app/assets/" + id.toString() + "/data" )
                .queryParam( "access_token", access_token ).type( MediaType.APPLICATION_OCTET_STREAM_TYPE ).put( data );

        InputStream is = resource().path( "/test-organization/test-app/assets/" + id.toString() + "/data" )
                .queryParam( "access_token", access_token ).get( InputStream.class );

        byte[] foundData = IOUtils.toByteArray( is );
        assertEquals( 7979, foundData.length );

        node = mapper.valueToTree(resource().path( "/test-organization/test-app/assets/my/clean/path" )
                .queryParam( "access_token", access_token ).accept( MediaType.APPLICATION_JSON_TYPE )
                .get( HashMap.class ));

        idNode = node.get( "entities" ).get( 0 ).get( "uuid" );
        assertEquals( id.toString(), idNode.asText() );
    }


    @Test
    public void octetStreamOnDynamicEntity() throws Exception {
        UserRepo.INSTANCE.load( resource(), access_token );

        Map<String, String> payload = hashMap( "name", "assetname" );

        JsonNode node = mapper.valueToTree(resource().path( "/test-organization/test-app/foos" ).queryParam( "access_token", access_token )
                .accept( MediaType.APPLICATION_JSON ).type( MediaType.APPLICATION_JSON_TYPE )
                .post( HashMap.class, payload ));

        JsonNode idNode = node.get( "entities" ).get( 0 ).get( "uuid" );
        String uuid = idNode.asText();
        assertNotNull( uuid );
        logNode( node );

        byte[] data = IOUtils.toByteArray( this.getClass().getResourceAsStream( "/cassandra_eye.jpg" ) );
        resource().path( "/test-organization/test-app/foos/" + uuid ).queryParam( "access_token", access_token )
                .type( MediaType.APPLICATION_OCTET_STREAM_TYPE ).put( data );

        // get entity
        node = mapper.valueToTree(resource().path( "/test-organization/test-app/foos/" + uuid ).queryParam( "access_token", access_token )
                .accept( MediaType.APPLICATION_JSON_TYPE ).get( HashMap.class ));
        logNode( node );
        Assert.assertEquals( "image/jpeg", node.findValue( AssetUtils.CONTENT_TYPE ).asText() );
        Assert.assertEquals( 7979, node.findValue( "content-length" ).asInt() );
        idNode = node.get( "entities" ).get( 0 ).get( "uuid" );
        assertEquals( uuid, idNode.asText() );

        // get data by UUID
        InputStream is =
                resource().path( "/test-organization/test-app/foos/" + uuid ).queryParam( "access_token", access_token )
                        .accept( MediaType.APPLICATION_OCTET_STREAM_TYPE ).get( InputStream.class );

        byte[] foundData = IOUtils.toByteArray( is );
        assertEquals( 7979, foundData.length );

        // get data by name
        is = resource().path( "/test-organization/test-app/foos/assetname" ).queryParam( "access_token", access_token )
                .accept( MediaType.APPLICATION_OCTET_STREAM_TYPE ).get( InputStream.class );

        foundData = IOUtils.toByteArray( is );
        assertEquals( 7979, foundData.length );
    }


    @Test
    public void multipartPostFormOnDynamicEntity() throws Exception {
        UserRepo.INSTANCE.load( resource(), access_token );

        byte[] data = IOUtils.toByteArray( this.getClass().getResourceAsStream( "/file-bigger-than-5M" ) );
        FormDataMultiPart form = new FormDataMultiPart().field( "file", data, MediaType.MULTIPART_FORM_DATA_TYPE );

        JsonNode node = mapper.valueToTree(resource().path( "/test-organization/test-app/foos" ).queryParam( "access_token", access_token )
                .accept( MediaType.APPLICATION_JSON ).type( MediaType.MULTIPART_FORM_DATA )
                .post( HashMap.class, form ));

        JsonNode idNode = node.get( "entities" ).get( 0 ).get( "uuid" );
        String uuid = idNode.asText();
        assertNotNull( uuid );
        logNode( node );

        // get entity
        node = mapper.valueToTree(resource().path( "/test-organization/test-app/foos/" + uuid ).queryParam( "access_token", access_token )
                .accept( MediaType.APPLICATION_JSON_TYPE ).get( HashMap.class ));
        logNode( node );
        assertEquals( "application/octet-stream", node.findValue( AssetUtils.CONTENT_TYPE ).asText() );
        assertEquals( 5324800, node.findValue( AssetUtils.CONTENT_LENGTH ).asInt() );
        idNode = node.get( "entities" ).get( 0 ).get( "uuid" );
        assertEquals( uuid, idNode.asText() );

        // get data
        InputStream is =
                resource().path( "/test-organization/test-app/foos/" + uuid ).queryParam( "access_token", access_token )
                        .accept( MediaType.APPLICATION_OCTET_STREAM_TYPE ).get( InputStream.class );

        byte[] foundData = IOUtils.toByteArray( is );
        assertEquals( 5324800, foundData.length );

        // delete
        node = mapper.valueToTree(resource().path( "/test-organization/test-app/foos/" + uuid ).queryParam( "access_token", access_token )
                .accept( MediaType.APPLICATION_JSON_TYPE ).delete( HashMap.class ));
    }


    @Test
    public void multipartPutFormOnDynamicEntity() throws Exception {
        UserRepo.INSTANCE.load( resource(), access_token );

        Map<String, String> payload = hashMap( "foo", "bar" );

        JsonNode node = mapper.valueToTree(resource().path( "/test-organization/test-app/foos" ).queryParam( "access_token", access_token )
                .accept( MediaType.APPLICATION_JSON ).type( MediaType.APPLICATION_JSON_TYPE )
                .post( HashMap.class, payload ));

        JsonNode idNode = node.get( "entities" ).get( 0 ).get( "uuid" );
        String uuid = idNode.asText();
        assertNotNull( uuid );
        logNode( node );

        // set file & assetname
        byte[] data = IOUtils.toByteArray( this.getClass().getResourceAsStream( "/cassandra_eye.jpg" ) );
        FormDataMultiPart form = new FormDataMultiPart().field( "foo", "bar2" )
                                                        .field( "file", data, MediaType.MULTIPART_FORM_DATA_TYPE );

        long created = System.currentTimeMillis();
        node = mapper.valueToTree(resource().path( "/test-organization/test-app/foos/" + uuid ).queryParam( "access_token", access_token )
                .accept( MediaType.APPLICATION_JSON ).type( MediaType.MULTIPART_FORM_DATA ).put( HashMap.class, form ));
        logNode( node );

        // get entity
        node = mapper.valueToTree(resource().path( "/test-organization/test-app/foos/" + uuid ).queryParam( "access_token", access_token )
                .accept( MediaType.APPLICATION_JSON_TYPE ).get( HashMap.class ));
        logNode( node );
        assertEquals( "image/jpeg", node.findValue( AssetUtils.CONTENT_TYPE ).asText() );
        assertEquals( 7979, node.findValue( AssetUtils.CONTENT_LENGTH ).asInt() );
        idNode = node.get( "entities" ).get( 0 ).get( "uuid" );
        assertEquals( uuid, idNode.asText() );
        JsonNode nameNode = node.get( "entities" ).get( 0 ).get( "foo" );
        assertEquals( "bar2", nameNode.asText() );
        long lastModified = node.findValue( AssetUtils.LAST_MODIFIED ).asLong();
        Assert.assertEquals( created, lastModified, 500 );

        // get data
        InputStream is =
                resource().path( "/test-organization/test-app/foos/" + uuid ).queryParam( "access_token", access_token )
                        .accept( "image/jpeg" ).get( InputStream.class );

        byte[] foundData = IOUtils.toByteArray( is );
        assertEquals( 7979, foundData.length );

        // post new data
        node = mapper.valueToTree(resource().path( "/test-organization/test-app/foos/" + uuid ).queryParam( "access_token", access_token )
                .accept( MediaType.APPLICATION_JSON ).type( MediaType.MULTIPART_FORM_DATA ).put( HashMap.class, form ));
        logNode( node );
        Assert.assertTrue( lastModified != node.findValue( AssetUtils.LAST_MODIFIED ).asLong() );
    }


    @Test
    @Ignore("Just enable and run when testing S3 large file upload specifically")
    public void largeFileInS3() throws Exception {
        UserRepo.INSTANCE.load( resource(), access_token );

        byte[] data = IOUtils.toByteArray( this.getClass().getResourceAsStream( "/file-bigger-than-5M" ) );
        FormDataMultiPart form = new FormDataMultiPart().field( "file", data, MediaType.MULTIPART_FORM_DATA_TYPE );

        // send data
        JsonNode node = mapper.valueToTree(resource().path( "/test-organization/test-app/foos" ).queryParam( "access_token", access_token )
                .accept( MediaType.APPLICATION_JSON ).type( MediaType.MULTIPART_FORM_DATA )
                .post( HashMap.class, form ));
        logNode( node );
        JsonNode idNode = node.get( "entities" ).get( 0 ).get( "uuid" );
        String uuid = idNode.asText();

        // get entity
        long timeout = System.currentTimeMillis() + 60000;
        while ( true ) {
            LOG.info( "Waiting for upload to finish..." );
            Thread.sleep( 2000 );
            node = mapper.valueToTree(resource().path( "/test-organization/test-app/foos/" + uuid )
                    .queryParam( "access_token", access_token ).accept( MediaType.APPLICATION_JSON_TYPE )
                    .get( HashMap.class ));
            logNode( node );

            // poll for the upload to complete
            if ( node.findValue( AssetUtils.E_TAG ) != null ) {
                break;
            }
            if ( System.currentTimeMillis() > timeout ) {
                throw new TimeoutException();
            }
        }
        LOG.info( "Upload complete!" );

        // get data
        InputStream is =
                resource().path( "/test-organization/test-app/foos/" + uuid ).queryParam( "access_token", access_token )
                        .accept( MediaType.APPLICATION_OCTET_STREAM_TYPE ).get( InputStream.class );

        byte[] foundData = IOUtils.toByteArray( is );
        assertEquals( 5324800, foundData.length );

        // delete
        node = mapper.valueToTree(resource().path( "/test-organization/test-app/foos/" + uuid ).queryParam( "access_token", access_token )
                .accept( MediaType.APPLICATION_JSON_TYPE ).delete( HashMap.class ));
    }
}