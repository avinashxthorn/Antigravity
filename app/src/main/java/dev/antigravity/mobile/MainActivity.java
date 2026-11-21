package dev.antigravity.mobile;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private final int BG=0xff101218, PANEL=0xff191d27, TEXT=0xffe5eaf5, MUTED=0xff9ca9bf, BLUE=0xff9bb8ff;
    private final int PICK=91, LIMIT=2*1024*1024;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private Uri tree, currentFile;
    private String currentName="", saved="", apiKey="", model="gemini-2.5-flash";
    private final ArrayList<String> dirs=new ArrayList<>(), names=new ArrayList<>();
    private final ArrayDeque<String> undo=new ArrayDeque<>(), redo=new ArrayDeque<>();
    private LinearLayout root, body, listing, toolbar;
    private EditText editor;
    private TextView title,status,lines;
    private boolean editingInternally=false, busy=false;
    private String mode="files";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String stored=getPreferences(0).getString("tree",null);
        if(stored!=null) {
            try { tree=Uri.parse(stored); dirs.add(DocumentsContract.getTreeDocumentId(tree)); names.add("Workspace"); }
            catch(Exception ex){ tree=null; }
        }
        build();
        showFiles();
    }

    private int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private GradientDrawable rounded(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView label(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);return t;}
    private Button button(String s,Runnable run){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(12);b.setTextColor(TEXT);b.setMinHeight(dp(44));b.setMinimumHeight(dp(44));b.setMinWidth(0);b.setPadding(dp(10),0,dp(10),0);b.setBackgroundTintList(ColorStateList.valueOf(PANEL));b.setOnClickListener(v->run.run());return b;}
    private void addButton(LinearLayout r,String s,Runnable run){r.addView(button(s,run),new LinearLayout.LayoutParams(0,dp(48),1));}
    private void build(){
        root=column();root.setBackgroundColor(BG);root.setPadding(dp(12),dp(8),dp(12),dp(8));setContentView(root);
        TextView brand=label("✦  ANTIGRAVITY / MOBILE",11,BLUE);brand.setLetterSpacing(.10f);root.addView(brand);
        title=label("Workspace",26,TEXT);title.setTypeface(null,Typeface.BOLD);root.addView(title);
        status=label("Choose a project folder to begin",12,MUTED);status.setPadding(0,dp(5),0,dp(8));root.addView(status);
        toolbar=row();addButton(toolbar,"Files",this::showFiles);addButton(toolbar,"Editor",this::showEditor);addButton(toolbar,"Assistant",this::showAssistant);root.addView(toolbar);
        body=column();root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        editor=new EditText(this);editor.setTextColor(TEXT);editor.setTypeface(Typeface.MONOSPACE);editor.setTextSize(14);editor.setGravity(Gravity.TOP|Gravity.START);
        editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setHorizontallyScrolling(true);editor.setPadding(dp(8),dp(10),dp(8),dp(10));editor.setBackgroundColor(BG);
        editor.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int st,int c,int a){if(!editingInternally){if(undo.size()>=80)undo.removeFirst();undo.addLast(s.toString());redo.clear();}}
            public void onTextChanged(CharSequence s,int st,int before,int count){refreshEditorStatus(); if(lines!=null) updateLines();}
            public void afterTextChanged(Editable e){}
        });
    }
    private void detach(View v){if(v.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)v.getParent()).removeView(v);}
    private void clear(String m,String header){ mode=m;detach(editor);body.removeAllViews();lines=null;title.setText(header); }
    private void setStatus(String s){status.setText(s);}
    private void fail(Exception e){setStatus("Error: "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));}
    private void refreshEditorStatus(){if(mode.equals("editor"))setStatus(currentFile==null?"No file open":currentName+(dirty()?" • Unsaved":" • Saved"));}
    private boolean dirty(){return currentFile!=null&&!editor.getText().toString().equals(saved);}
    private void loadText(String s){editingInternally=true;editor.setText(s);editor.setSelection(0);editingInternally=false;undo.clear();redo.clear();}
    private void guard(Runnable action){if(busy){setStatus("A file operation is running");return;}if(!dirty()){action.run();return;}
        new AlertDialog.Builder(this).setTitle("Unsaved changes").setMessage("Save before continuing?").setPositiveButton("Save",(d,w)->save(action)).setNeutralButton("Discard",(d,w)->{loadText(saved);action.run();}).setNegativeButton("Cancel",null).show();}
    private void pick(){guard(()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);startActivityForResult(i,PICK);});}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request!=PICK||result!=RESULT_OK||data==null||data.getData()==null)return;
        try{Uri chosen=data.getData();int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            getContentResolver().takePersistableUriPermission(chosen,flags);
            tree=chosen;getPreferences(0).edit().putString("tree",tree.toString()).apply();dirs.clear();names.clear();dirs.add(DocumentsContract.getTreeDocumentId(tree));names.add("Workspace");currentFile=null;saved="";loadText("");showFiles();
        }catch(Exception e){fail(e);}
    }
    private Uri document(String id){return DocumentsContract.buildDocumentUriUsingTree(tree,id);}
    private Uri directory(){return document(dirs.get(dirs.size()-1));}
    private static class Entry{String id,name,mime;Entry(String i,String n,String m){id=i;name=n;mime=m;}boolean folder(){return DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);}}
    private void showFiles(){
        clear("files","Explorer");
        LinearLayout actions=row();addButton(actions,"Open folder",this::pick);addButton(actions,"New file",()->create(false));addButton(actions,"New folder",()->create(true));body.addView(actions);
        if(tree==null){setStatus("No workspace selected");TextView empty=label("Your code, on your device.\n\nOpen a folder to browse and edit local project files.",18,MUTED);empty.setPadding(dp(6),dp(30),dp(6),0);body.addView(empty);return;}
        setStatus(String.join(" / ",names));
        if(dirs.size()>1)body.addView(button("← Parent directory",()->{dirs.remove(dirs.size()-1);names.remove(names.size()-1);showFiles();}));
        ScrollView scroll=new ScrollView(this);listing=column();scroll.addView(listing);body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        try{
            Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,dirs.get(dirs.size()-1));ArrayList<Entry> entries=new ArrayList<>();
            String[] p={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE};
            try(Cursor c=getContentResolver().query(children,p,null,null,null)){if(c==null)throw new IOException("Directory unavailable");while(c.moveToNext())entries.add(new Entry(c.getString(0),c.getString(1),c.getString(2)));}
            entries.sort(Comparator.comparing((Entry e)->!e.folder()).thenComparing(e->e.name,String.CASE_INSENSITIVE_ORDER));
            if(entries.isEmpty())listing.addView(label("This folder is empty.",14,MUTED));
            for(Entry e:entries){
                LinearLayout item=row();item.setBackground(rounded(PANEL,10));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(56));lp.bottomMargin=dp(5);listing.addView(item,lp);
                TextView t=label((e.folder()?"▸  ":"·  ")+e.name,15,TEXT);t.setPadding(dp(12),0,0,0);item.addView(t,new LinearLayout.LayoutParams(0,-1,1));t.setGravity(Gravity.CENTER_VERTICAL);
                t.setOnClickListener(v->{if(e.folder()){dirs.add(e.id);names.add(e.name);showFiles();}else guard(()->open(e));});item.addView(button("⋯",()->entryMenu(e)),new LinearLayout.LayoutParams(dp(48),dp(48)));
            }
        }catch(Exception e){fail(e);}
    }
    private void askName(String title,String initial,java.util.function.Consumer<String> fn){
        EditText input=new EditText(this);input.setSingleLine(true);input.setText(initial);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setView(input).setPositiveButton("OK",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(w->{String s=input.getText().toString().trim();if(s.isEmpty()||s.equals(".")||s.equals("..")||s.contains("/")||s.contains("\\")){input.setError("Enter a valid name");return;}dialog.dismiss();fn.accept(s);}));dialog.show();
    }
    private void create(boolean folder){if(tree==null){pick();return;}askName(folder?"New folder":"New file","",n->{try{
        Uri u=DocumentsContract.createDocument(getContentResolver(),directory(),folder?DocumentsContract.Document.MIME_TYPE_DIR:"text/plain",n);
        if(u==null)throw new IOException("Provider declined creation");showFiles();
    }catch(Exception e){fail(e);}});}
    private void entryMenu(Entry e){new AlertDialog.Builder(this).setTitle(e.name).setItems(new String[]{"Rename","Delete"},(d,w)->guard(()->{if(w==0)askName("Rename",e.name,n->{try{
        Uri old=document(e.id),u=DocumentsContract.renameDocument(getContentResolver(),old,n);if(u==null)throw new IOException("Rename failed");if(old.equals(currentFile)){currentFile=u;currentName=n;}showFiles();
    }catch(Exception x){fail(x);}});else new AlertDialog.Builder(this).setTitle("Delete "+e.name+"?").setMessage("This permanently removes the selected item, including folder contents if supported by your storage provider.").setNegativeButton("Cancel",null).setPositiveButton("Delete",(a,b)->{try{
        Uri u=document(e.id);if(!DocumentsContract.deleteDocument(getContentResolver(),u))throw new IOException("Delete failed");if(u.equals(currentFile)||e.folder()){currentFile=null;saved="";loadText("");}showFiles();
    }catch(Exception x){fail(x);}}).show();})).show();}
    private void open(Entry entry){
        Uri uri=document(entry.id);busy=true;setStatus("Reading "+entry.name+"…");
        io.execute(()->{try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            if(in==null)throw new IOException("Cannot open file");byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>LIMIT)throw new IOException("File exceeds 2 MiB");out.write(buf,0,n);}
            String s=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray())).toString();if(s.indexOf(0)>=0)throw new IOException("Binary files are not editable");
            runOnUiThread(()->{busy=false;currentFile=uri;currentName=entry.name;saved=s;loadText(s);showEditor();});
        }catch(Exception ex){runOnUiThread(()->{busy=false;fail(ex);});}});
    }
    private void showEditor(){
        clear("editor","Code editor");LinearLayout actions=row();addButton(actions,"Save",()->save(null));addButton(actions,"Undo",()->history(undo,redo));addButton(actions,"Redo",()->history(redo,undo));addButton(actions,"Find",this::find);body.addView(actions);
        if(currentFile==null){refreshEditorStatus();body.addView(label("Select a text file in Explorer.",16,MUTED));return;}
        FrameLayout frame=new FrameLayout(this);frame.setBackground(rounded(PANEL,8));frame.addView(editor,new FrameLayout.LayoutParams(-1,-1));body.addView(frame,new LinearLayout.LayoutParams(-1,0,1));refreshEditorStatus();
    }
    private void updateLines(){if(lines!=null)lines.setText("");}
    private void history(ArrayDeque<String> from,ArrayDeque<String> to){if(from.isEmpty())return;to.addLast(editor.getText().toString());editingInternally=true;editor.setText(from.removeLast());editingInternally=false;}
    private void find(){if(currentFile==null)return;askName("Find text","",s->{String t=editor.getText().toString();int start=Math.max(0,editor.getSelectionEnd()),i=t.indexOf(s,start);if(i<0)i=t.indexOf(s);if(i<0){setStatus("No match");return;}editor.requestFocus();editor.setSelection(i,i+s.length());});}
    private void save(Runnable done){
        if(currentFile==null||busy){setStatus("No file available to save, or operation in progress");return;}
        Uri uri=currentFile;String value=editor.getText().toString();busy=true;editor.setEnabled(false);setStatus("Saving…");
        io.execute(()->{try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("Cannot write file");out.write(value.getBytes(StandardCharsets.UTF_8));out.flush();
        }catch(Exception e){runOnUiThread(()->{busy=false;editor.setEnabled(true);fail(e);});return;}
        runOnUiThread(()->{busy=false;editor.setEnabled(true);saved=value;setStatus("Saved "+currentName);if(done!=null)done.run();});});
    }
    private void showAssistant(){
        clear("assistant","AI assistant");setStatus("Gemini API • only explicitly selected context is sent");
        LinearLayout settings=row();addButton(settings,"API settings",this::apiSettings);body.addView(settings);
        EditText prompt=new EditText(this);prompt.setTextColor(TEXT);prompt.setHint("Describe a code change or ask a question");prompt.setMinLines(3);body.addView(prompt,new LinearLayout.LayoutParams(-1,-2));
        CheckBox include=new CheckBox(this);include.setText("Include open file ("+(currentFile==null?"none":currentName)+")");include.setTextColor(TEXT);include.setEnabled(currentFile!=null);body.addView(include);
        TextView result=label("Connect your own API key to start.",15,TEXT);result.setTextIsSelectable(true);ScrollView scroll=new ScrollView(this);scroll.addView(result);body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        Button send=button("Send request",()->{});send.setOnClickListener(v->{if(apiKey.isEmpty()){apiSettings();return;}if(prompt.getText().toString().trim().isEmpty()){prompt.setError("Enter a prompt");return;}String input=prompt.getText().toString();boolean attach=include.isChecked()&&currentFile!=null;String source=attach?editor.getText().toString():"";String name=currentName;
            Runnable call=()->requestAi(input+(attach?"\n\nOpen file: "+name+"\n"+source:""),result,send);
            if(attach)new AlertDialog.Builder(this).setTitle("Send file to Gemini?").setMessage("The open file will be transmitted to Google's Gemini API. Remove secrets before continuing.").setPositiveButton("Send",(d,w)->call.run()).setNegativeButton("Cancel",null).show();else call.run();
        });body.addView(send);body.addView(button("Replace editor with response",()->{if(currentFile==null||!Boolean.TRUE.equals(result.getTag()))return;new AlertDialog.Builder(this).setTitle("Replace open file?").setMessage("Review output before saving. This replaces the editor buffer, not the disk file.").setNegativeButton("Cancel",null).setPositiveButton("Replace",(d,w)->{editor.setText(result.getText());showEditor();}).show();}));
    }
    private void apiSettings(){
        LinearLayout form=column();form.setPadding(dp(20),0,dp(20),0);EditText key=new EditText(this);key.setHint("Gemini API key (session only)");key.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);key.setText(apiKey);form.addView(key);
        EditText m=new EditText(this);m.setHint("Model ID");m.setText(model);form.addView(m);
        new AlertDialog.Builder(this).setTitle("Gemini connection").setView(form).setPositiveButton("Use",(d,w)->{apiKey=key.getText().toString().trim();String id=m.getText().toString().trim();if(id.matches("[A-Za-z0-9._-]+"))model=id;else setStatus("Invalid model ID");}).setNegativeButton("Cancel",null).show();
    }
    private void requestAi(String prompt,TextView result,Button send){
        String token=apiKey,id=model;send.setEnabled(false);result.setTag(false);result.setText("Waiting for Gemini response…");
        io.execute(()->{HttpURLConnection c=null;try{
            URL url=new URL("https://generativelanguage.googleapis.com/v1beta/models/"+id+":generateContent");c=(HttpURLConnection)url.openConnection();c.setConnectTimeout(20000);c.setReadTimeout(90000);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("x-goog-api-key",token);
            JSONObject payload=new JSONObject().put("contents",new JSONArray().put(new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("text",prompt)))));
            try(OutputStream out=c.getOutputStream()){out.write(payload.toString().getBytes(StandardCharsets.UTF_8));}
            int http=c.getResponseCode();if(http<200||http>=300)throw new IOException("Gemini HTTP "+http+" — check key, quota and model");
            String raw;try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>LIMIT)throw new IOException("Response too large");out.write(b,0,n);}raw=out.toString("UTF-8");}
            JSONArray parts=new JSONObject(raw).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts");StringBuilder answer=new StringBuilder();for(int i=0;i<parts.length();i++)answer.append(parts.getJSONObject(i).optString("text"));if(answer.length()==0)throw new IOException("No text returned");
            runOnUiThread(()->{result.setText(answer.toString());result.setTag(true);send.setEnabled(true);});
        }catch(Exception ex){runOnUiThread(()->{result.setText("Request failed: "+ex.getMessage());send.setEnabled(true);});}finally{if(c!=null)c.disconnect();}});
    }
    @Override public void onBackPressed(){if(mode.equals("files")&&dirs.size()>1){dirs.remove(dirs.size()-1);names.remove(names.size()-1);showFiles();}else guard(()->super.onBackPressed());}
}
