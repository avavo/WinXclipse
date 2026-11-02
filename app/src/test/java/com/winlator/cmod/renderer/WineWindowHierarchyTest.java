package com.winlator.cmod.renderer;

import android.app.Application;
import android.content.Context;
import com.winlator.cmod.widget.XServerView;
import org.robolectric.RuntimeEnvironment;
import com.winlator.cmod.xconnector.*;
import com.winlator.cmod.xserver.*;
import com.winlator.cmod.xserver.errors.BadMatch;
import com.winlator.cmod.xserver.events.Event;
import com.winlator.cmod.xserver.events.ReparentNotify;
import com.winlator.cmod.xserver.requests.WindowRequests;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Real X11 hierarchy/scene code, without loading a GPU or an Android native library. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class, shadows={WineWindowHierarchyTest.ShadowDrawable.class, WineWindowHierarchyTest.ShadowView.class})
public class WineWindowHierarchyTest {
    @Implements(Drawable.class)
    public static class ShadowDrawable {
        @Implementation protected static void __staticInitializer__() {}
    }
    @Implements(XServerView.class)
    public static class ShadowView extends org.robolectric.shadows.ShadowGLSurfaceView {
        @Implementation protected void __constructor__(Context context,XServer server,String backend) {}
        @Implementation protected void queueEvent(Runnable event){event.run();}
        @Implementation protected void requestRender(){}
    }
    private XServer server;
    private Window root;
    private int nextId;
    @Before public void setup() {
        server=new XServer(new ScreenInfo(128,72));
        root=server.windowManager.rootWindow;
        nextId=10000;
    }
    private Window window(Window parent,int x,int y,boolean mapped) throws Exception {
        Window w=server.windowManager.createWindow(nextId++,parent,(short)x,(short)y,(short)64,(short)36,
                WindowAttributes.WindowClass.INPUT_OUTPUT,null,(byte)0,null);
        w.attributes.setMapped(mapped);
        return w;
    }
    private static final class Output extends XOutputStream {
        Output(){super(2048);setByteOrder(ByteOrder.LITTLE_ENDIAN);}
        @Override public XStreamLock lock(){return ()->{};}
        ByteBuffer bytes(){ByteBuffer b=buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);b.flip();return b;}
    }
    private static XInputStream input(ByteBuffer data) {
        data.flip();XInputStream in=new XInputStream(32);
        ReflectionHelpers.setField(in,"activeBuffer",data);return in;
    }
    private static final class Scene extends GLRenderer {
        int reparents;
        Scene(XServer s){super(new XServerView(RuntimeEnvironment.getApplication(),s,"opengl"),s);updateScene();}
        @Override protected Drawable createRootCursorDrawable(){return new Drawable(0,1,1,null);}
        @Override public void onChangeWindowZOrder(Window w){reparents++;super.onChangeWindowZOrder(w);}
        RenderableWindow find(Window w){
            for(RenderableWindow r:renderableWindows)if(r.content==w.getContent())return r;
            return null;
        }
    }
    @Test public void wireReparentPreservesSignedCoordinatesAndDrawableIdentity() throws Exception {
        Window old=window(root,-200,-100,true),parent=window(root,20,10,true),client=window(old,5,7,true);
        Drawable original=client.getContent();
        ByteBuffer data=ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        data.putInt(client.id).putInt(parent.id).putShort((short)-3).putShort((short)9);
        XInputStream in=input(data);Output out=new Output();
        WindowRequests.reparentWindow(new XClient(server,in,out),in,out);
        assertEquals(0,in.available());assertSame(parent,client.getParent());
        assertEquals(-3,client.getX());assertEquals(9,client.getY());
        assertEquals(17,client.getRootX());assertEquals(19,client.getRootY());
        assertSame(original,client.getContent());assertEquals(client.id,original.id);
        assertTrue(client.attributes.isMapped());
    }
    @Test public void mappedVulkanChildReparentedFromDummyParentEntersTheScene() throws Exception {
        Window dummy=window(root,-1000,-1000,false),whole=window(root,20,10,true),client=window(dummy,0,0,true);
        Scene scene=new Scene(server);assertNull(scene.find(client));
        server.windowManager.reparentWindow(client,whole,(short)2,(short)4);
        RenderableWindow shown=scene.find(client);
        assertNotNull(shown);assertEquals(22,shown.rootX);assertEquals(14,shown.rootY);
        assertEquals(1,scene.reparents);
    }
    @Test public void reparentIntoHiddenParentRemovesTheVulkanChildFromTheScene() throws Exception {
        Window whole=window(root,0,0,true),dummy=window(root,-1000,-1000,false),client=window(whole,0,0,true);
        Scene scene=new Scene(server);assertNotNull(scene.find(client));
        server.windowManager.reparentWindow(client,dummy,(short)0,(short)0);
        assertNull(scene.find(client));assertTrue(client.attributes.isMapped());
        assertEquals(Window.MapState.UNVIEWABLE,client.getMapState());
    }
    @Test public void movingWineParentMovesNestedVulkanWindowAndFullscreenInputMap() throws Exception {
        Window whole=window(root,10,5,true),middle=window(whole,2,3,true),client=window(middle,4,6,true);
        Window other=window(root,1,1,true);
        Scene scene=new Scene(server);
        scene.renderableWindows.add(new RenderableWindow(client.getContent(),16,14,true));
        whole.setX((short)40);whole.setY((short)20);
        scene.onUpdateWindowGeometry(whole,false);
        assertEquals(46,scene.find(client).rootX);assertEquals(29,scene.find(client).rootY);
        assertEquals(42,scene.find(middle).rootX);assertEquals(23,scene.find(middle).rootY);
        assertEquals(1,scene.find(other).rootX);assertEquals(1,scene.find(other).rootY);
        assertNotNull(scene.forcedWindowMap);assertEquals(46,scene.forcedWindowMap[0]);
        assertEquals(29,scene.forcedWindowMap[1]);
    }
    @Test public void inputOnlyWindowCannotOccludeOrCrashTheScene() throws Exception {
        Window whole=window(root,0,0,true);
        Window input=server.windowManager.createWindow(nextId++,whole,(short)0,(short)0,(short)128,(short)72,
                WindowAttributes.WindowClass.INPUT_ONLY,null,(byte)0,null);
        input.attributes.setMapped(true);
        Scene scene=new Scene(server);
        assertEquals(1,scene.renderableWindows.size());assertSame(whole.getContent(),scene.renderableWindows.get(0).content);
    }
    @Test public void unmappedWindowIsNotMappedByReparent() throws Exception {
        Window old=window(root,0,0,true),parent=window(root,10,10,true),client=window(old,0,0,false);
        List<String> changes=new ArrayList<>();
        server.windowManager.addOnWindowModificationListener(new WindowManager.OnWindowModificationListener(){
            @Override public void onMapWindow(Window w){changes.add("map");}
            @Override public void onUnmapWindow(Window w){changes.add("unmap");}
            @Override public void onReparentWindow(Window w){changes.add("reparent");}
        });
        server.windowManager.reparentWindow(client,parent,(short)7,(short)-8);
        assertFalse(client.attributes.isMapped());assertEquals(Collections.singletonList("reparent"),changes);
    }
    @Test public void reparentUnmapsNotifiesAndRemapsInProtocolOrder() throws Exception {
        Window old=window(root,0,0,true),parent=window(root,0,0,true),client=window(old,0,0,true);
        Output out=new Output();XClient listener=new XClient(server,new XInputStream(32),out);
        listener.setEventListenerForWindow(client,new Bitmask(Event.STRUCTURE_NOTIFY));
        listener.setEventListenerForWindow(old,new Bitmask(Event.SUBSTRUCTURE_NOTIFY));
        listener.setEventListenerForWindow(parent,new Bitmask(Event.SUBSTRUCTURE_NOTIFY));
        server.windowManager.reparentWindow(client,parent,(short)-12,(short)23);
        ByteBuffer b=out.bytes();assertEquals(224,b.remaining());
        int[] expected={18,18,21,21,21,19,19};
        for(int n=0;n<expected.length;n++)assertEquals("event "+n,expected[n],b.get(n*32)&255);
        assertEquals(client.id,b.getInt(64+4));assertEquals(parent.id,b.getInt(64+12));
        assertEquals(-12,b.getShort(64+16));assertEquals(23,b.getShort(64+18));
    }
    @Test public void remapStillRespectsTheNewParentsRedirect() throws Exception {
        Window old=window(root,0,0,true),parent=window(root,0,0,true),client=window(old,0,0,true);
        Output out=new Output();XClient listener=new XClient(server,new XInputStream(32),out);
        listener.setEventListenerForWindow(parent,new Bitmask(Event.SUBSTRUCTURE_REDIRECT));
        server.windowManager.reparentWindow(client,parent,(short)0,(short)0);
        assertSame(parent,client.getParent());assertFalse(client.attributes.isMapped());
        ByteBuffer b=out.bytes();assertEquals(32,b.remaining());assertEquals(20,b.get()&255);
        assertEquals(parent.id,b.getInt(4));assertEquals(client.id,b.getInt(8));
    }
    @Test public void sameParentReparentMovesWindowToTopWithoutDuplicatingIt() throws Exception {
        Window parent=window(root,0,0,true),client=window(parent,0,0,true),other=window(parent,0,0,true);
        server.windowManager.reparentWindow(client,parent,(short)3,(short)4);
        assertEquals(2,parent.getChildCount());assertSame(other,parent.getChildren().get(0));
        assertSame(client,parent.getChildren().get(1));
    }
    @Test public void invalidReparentsDoNotMutateTheTree() throws Exception {
        Window parent=window(root,0,0,true),client=window(parent,0,0,true),child=window(client,0,0,true);
        for(Window target:new Window[]{client,child}) {
            assertThrows(BadMatch.class,()->server.windowManager.reparentWindow(client,target,(short)9,(short)9));
            assertSame(parent,client.getParent());assertEquals(0,client.getX());assertTrue(client.attributes.isMapped());
        }
        assertThrows(BadMatch.class,()->server.windowManager.reparentWindow(root,parent,(short)0,(short)0));
    }
    @Test public void inputOutputCannotBeReparentedIntoInputOnly() throws Exception {
        Window client=window(root,0,0,true);
        Window input=server.windowManager.createWindow(nextId++,root,(short)0,(short)0,(short)10,(short)10,
                WindowAttributes.WindowClass.INPUT_ONLY,null,(byte)0,null);
        assertThrows(BadMatch.class,()->server.windowManager.reparentWindow(client,input,(short)0,(short)0));
        assertSame(root,client.getParent());
    }
    @Test public void reparentEventSnapshotsCoordinatesAtGeneration() throws Exception {
        Window parent=window(root,0,0,true),client=window(parent,-5,7,true);
        Event event=new ReparentNotify(parent,client,parent);client.setX((short)22);
        Output out=new Output();event.send((short)37,out);ByteBuffer b=out.bytes();
        assertEquals(32,b.remaining());assertEquals(21,b.get()&255);assertEquals(37,b.getShort(2));
        assertEquals(parent.id,b.getInt(4));assertEquals(client.id,b.getInt(8));
        assertEquals(-5,b.getShort(16));assertEquals(7,b.getShort(18));
    }
}
