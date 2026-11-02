package com.winlator.cmod.contents;
import android.app.Application;
import android.content.Context;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public class FexCatalogIdentityTest {
    @Test public void catalogIdentitiesMatchAllPublishedFexManifestsIncluding2508And2609() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        String json;
        try(java.io.InputStream in=context.getAssets().open("contents.json")) {
            json=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }
        JSONArray catalog=new JSONArray(json);int checked=0;
        ContentsManager manager=new ContentsManager(context);
        for(int i=0;i<catalog.length();i++) {
            JSONObject item=catalog.getJSONObject(i);if(!"FEXCore".equals(item.getString("type")))continue;
            String url=item.getString("remoteUrl"),asset=url.substring(url.lastIndexOf('/')+1).replace(".wcp",".json");
            File manifest=new File(context.getCacheDir(),asset);
            try(java.io.InputStream in=getClass().getResourceAsStream("/fex-manifests/"+asset)) {
                assertNotNull("Missing published manifest for "+url,in);
                Files.write(manifest.toPath(),in.readAllBytes());
            }
            ContentProfile profile=manager.readProfile(manifest);assertNotNull(url,profile);
            assertEquals(url,item.getString("verName"),profile.verName);
            assertEquals(url,item.getInt("verCode"),profile.verCode);
            assertTrue(com.winlator.cmod.core.ContentVersion.samePackageName("FEXCore",profile.verName,item.getString("verName")));
            checked++;
        }
        assertEquals(15,checked);
    }

    @Test public void installedPlainVersionHidesOldGithubAssetIdButPreservesDifferentBuild() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        ContentProfile local=new ContentProfile();local.type=ContentProfile.ContentType.CONTENT_TYPE_FEXCORE;
        local.verName="2607";local.verCode=0;
        File directory=ContentsManager.getInstallDir(context,local);assertTrue(directory.mkdirs()||directory.isDirectory());
        JSONObject json=new JSONObject().put("type","FEXCore").put("versionName","2607").put("versionCode",0).put("description","").put("files",new JSONArray());
        Files.write(new File(directory,ContentsManager.PROFILE_NAME).toPath(),json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        JSONArray remote=new JSONArray();
        remote.put(new JSONObject().put("type","FEXCore").put("verName","2607").put("verCode",693839161)
                .put("remoteUrl","https://github.com/avavo/WinXclipse/releases/download/runtime-fexcore-v0.8/2607.wcp"));
        remote.put(new JSONObject().put("type","FEXCore").put("verName","2607").put("verCode",12)
                .put("remoteUrl","https://github.com/avavo/WinXclipse/releases/download/runtime-fexcore-v0.8/2607-12.wcp"));
        ContentsManager manager=new ContentsManager(context);manager.setRemoteProfiles(remote.toString());
        List<ContentProfile> profiles=manager.getProfiles(local.type);
        assertEquals(2,profiles.size());assertEquals(0,profiles.get(0).verCode);assertEquals(12,profiles.get(1).verCode);
    }
}
