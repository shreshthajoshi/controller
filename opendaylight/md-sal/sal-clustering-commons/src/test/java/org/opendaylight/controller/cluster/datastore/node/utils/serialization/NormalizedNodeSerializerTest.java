package org.opendaylight.controller.cluster.datastore.node.utils.serialization;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.opendaylight.controller.cluster.datastore.util.TestModel;
import org.opendaylight.controller.protobuff.messages.common.NormalizedNodeMessages;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class NormalizedNodeSerializerTest {

    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    @Test
    public void testSerializeDeSerialize(){

        // This test basically serializes and deSerializes a largish document
        // which contains most of the types of nodes that go into a normalized
        // node and uses several value types as well. It is in general a good
        // sanity test which could be augmented with specific unit tests.

        long start = System.nanoTime();

        NormalizedNode<?, ?> expectedNode =
            TestModel.createDocumentOne(TestModel.createTestContext());

        NormalizedNodeMessages.Node expected = NormalizedNodeSerializer
            .serialize(expectedNode);

        System.out.println("Serialize Time = " + (System.nanoTime() - start)/1000000);

        System.out.println("Serialized Size = " + expected.getSerializedSize());

        System.out.println(expected.toString());

        start = System.nanoTime();

        NormalizedNode actualNode =
            NormalizedNodeSerializer.deSerialize(expected);

        System.out.println("DeSerialize Time = " + (System.nanoTime() - start)/1000000);

        // Compare the original normalized node to the normalized node that was
        // created by serializing the original node and deSerializing it back.
        assertEquals(expectedNode, actualNode);

    }

    @Test(expected = NullPointerException.class)
    public void testSerializeNullNormalizedNode(){
        assertNotNull(NormalizedNodeSerializer.serialize(null));
    }

    @Test
    public void testDeSerializeNullProtocolBufferNode(){
        expectedException.expect(NullPointerException.class);
        expectedException.expectMessage("node should not be null");

        NormalizedNodeSerializer.deSerialize(null);
    }

    @Test
    public void testDeSerializePathArgumentNullNode(){
        expectedException.expect(NullPointerException.class);
        expectedException.expectMessage("node should not be null");

        NormalizedNodeSerializer
            .deSerialize(null, NormalizedNodeMessages.PathArgument.getDefaultInstance());
    }

    @Test
    public void testDeSerializePathArgumentNullPathArgument(){
        expectedException.expect(NullPointerException.class);
        expectedException.expectMessage("pathArgument should not be null");

        NormalizedNodeSerializer.deSerialize(NormalizedNodeMessages.Node.getDefaultInstance() , null);
    }

    @Test
    public void testDeSerializePathArgument(){

        NormalizedNodeMessages.Node.Builder nodeBuilder = NormalizedNodeMessages.Node.newBuilder();

        nodeBuilder.addCode("urn:opendaylight:params:xml:ns:yang:controller:md:sal:dom:store:test1");
        nodeBuilder.addCode("urn:opendaylight:params:xml:ns:yang:controller:md:sal:dom:store:test");


        nodeBuilder.addCode("2014-04-13");
        nodeBuilder.addCode("2014-05-13");
        nodeBuilder.addCode("2014-03-13");

        nodeBuilder.addCode("dummy1");
        nodeBuilder.addCode("dummy2");
        nodeBuilder.addCode("dummy3");
        nodeBuilder.addCode("capability");



        NormalizedNodeMessages.PathArgument.Builder pathBuilder = NormalizedNodeMessages.PathArgument.newBuilder();

        pathBuilder.setIntType(PathArgumentType.NODE_IDENTIFIER.ordinal());

        NormalizedNodeMessages.QName.Builder qNameBuilder = NormalizedNodeMessages.QName.newBuilder();
        qNameBuilder.setNamespace(1);
        qNameBuilder.setRevision(4);
        qNameBuilder.setLocalName(8);

        pathBuilder.setNodeType(qNameBuilder);

        YangInstanceIdentifier.PathArgument pathArgument =
            NormalizedNodeSerializer
                .deSerialize(nodeBuilder.build(), pathBuilder.build());

        assertNotNull(pathArgument);

        assertEquals("urn:opendaylight:params:xml:ns:yang:controller:md:sal:dom:store:test", pathArgument.getNodeType().getNamespace().toString());
        assertEquals("2014-03-13", pathArgument.getNodeType().getFormattedRevision());
        assertEquals("capability", pathArgument.getNodeType().getLocalName());
    }


}