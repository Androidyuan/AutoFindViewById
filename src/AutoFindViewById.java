import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.application.Result;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import com.intellij.psi.impl.source.PsiJavaFileImpl;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.apache.http.util.TextUtils;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Created by yuancong on 16/7/25.
 */
public class AutoFindViewById extends AnAction {

    @Override
    public void actionPerformed(AnActionEvent e) {

        Project project = e.getRequiredData(CommonDataKeys.PROJECT);
        PsiFile psiFile = e.getData(LangDataKeys.PSI_FILE);

        if(project == null || psiFile == null){
            return;
        }

        String fileName = Messages.showInputDialog(project, "Please input layout xml :\nexample : R.layout.example or example.xml\n", "Input Layout Xml", null);

        if(!TextUtils.isEmpty(fileName)) {
            if(fileName.contains("R.layout.")) {
                autoFindViewById(project, fileName.substring(9) + ".xml", psiFile);
            }else{
                autoFindViewById(project, fileName, psiFile);
            }
        }

    }

    void autoFindViewById(Project project, String fileName, PsiFile psiFile){
        final PsiFile[] files  = FilenameIndex.getFilesByName(project, fileName, GlobalSearchScope.projectScope(project));

        if(files == null || files.length == 0){
            Messages.showErrorDialog(project, "Can not find " + fileName + " in project", "Error");
            return;
        }

        Object action = new WriteCommandAction(project, "autoFindViewById", psiFile) {
            @Override
            protected void run(@NotNull Result result) throws Throwable {
                HashMap<String, String> viewIds = new HashMap<>();

                for(PsiFile file : files){
                    findAllIds(viewIds, ((XmlFile)file).getRootTag(), false);
                }
                if(viewIds.isEmpty()){
                    return;
                }
                PsiElementFactory mFactory = JavaPsiFacade.getElementFactory(project);
                PsiClass activityClass = JavaPsiFacade.getInstance(project).findClass("android.app.Activity", GlobalSearchScope.allScope(project));
                PsiClass fragmentClass = JavaPsiFacade.getInstance(project).findClass("android.app.Fragment", GlobalSearchScope.allScope(project));
                PsiClass supportFragmentClass = JavaPsiFacade.getInstance(project).findClass("android.support.v4.app.Fragment", GlobalSearchScope.allScope(project));
                PsiClass clazz = JavaPsiFacade.getInstance(project).findClass(((PsiJavaFileImpl)psiFile).getPackageName() + "." + psiFile.getName().split("\\.")[0], GlobalSearchScope.projectScope(project));

                if(activityClass != null && clazz.isInheritor(activityClass, true)){
                    createInitViewMethodAndViewFields(clazz, mFactory, viewIds, false);
                    generateInActivity(clazz, "R.layout." + fileName.split("\\.")[0], mFactory);
                } else if((fragmentClass != null && clazz.isInheritor(fragmentClass, true)) || (supportFragmentClass != null && clazz.isInheritor(supportFragmentClass, true))){
                    createInitViewMethodAndViewFields(clazz, mFactory, viewIds, true);
                    generateInFragment(clazz, "R.layout." + fileName.split("\\.")[0], mFactory);
                }

            }
        };
        ((WriteCommandAction) action).execute();
    }

    void findAllIds(HashMap map, XmlTag tag, boolean needIgnoreCurTag){
        boolean needIgnore = false;
        if(!needIgnoreCurTag) {
            String[] nameArr = tag.getName().split("\\.");
            String name = nameArr[nameArr.length - 1];
            if (name.equalsIgnoreCase("merge")) {
                needIgnore = true;
            } else {
                boolean hasId = false;
                XmlAttribute attribute = tag.getAttribute("android:id");
                if (attribute != null) {
                    String displayValue = attribute.getDisplayValue();
                    if (!TextUtils.isEmpty(displayValue)) {
                        String[] value = displayValue.split("/");
                        if (value.length == 2) {
                            hasId = true;
                            map.put(value[1], name);
                        }
                    }
                }
                if (name.equalsIgnoreCase("include")) {
                    XmlAttribute layout = tag.getAttribute("layout");
                    if (layout != null) {
                        String displayValue = layout.getDisplayValue();
                        if(!TextUtils.isEmpty(displayValue)) {
                            String[] layoutArr = displayValue.split("/");
                            if(layoutArr.length == 2) {
                                PsiFile[] files = FilenameIndex.getFilesByName(tag.getProject(), layoutArr[1] + ".xml", GlobalSearchScope.projectScope(tag.getProject()));
                                for (PsiFile file : files) {
                                    findAllIds(map, ((XmlFile) file).getRootTag(), hasId);
                                }
                            }
                        }
                    }
                }

            }
        }
        XmlTag[] subTags = tag.getSubTags();
        if(subTags != null && subTags.length != 0){
            for(XmlTag t : subTags){
                findAllIds(map, t, needIgnore);
            }
        }
    }

    void createInitViewMethodAndViewFields(PsiClass clazz, PsiElementFactory factory, HashMap viewIds, boolean isFragment){
        StringBuilder initView = new StringBuilder();
        initView.append("private void initView(" + (isFragment ? "View rootView" : "") +") {\n");
        Iterator iterator = viewIds.entrySet().iterator();
        while(iterator.hasNext()){
            Map.Entry entry = (Map.Entry) iterator.next();
            String viewId = (String) entry.getKey();
            String viewType = (String) entry.getValue();
            StringBuilder viewName = new StringBuilder();
            viewName.append("m");
            for(String s : viewId.split("_")){
                viewName.append(s.substring(0, 1).toUpperCase());
                viewName.append(s.substring(1).toLowerCase());
            }
            clazz.add(factory.createFieldFromText("private " + entry.getValue() + " " + viewName.toString() + ";\n",clazz));
            initView.append(viewName.toString() + " = " + ((viewType.equals("View") || viewType.equals("android.view.View")) ? "" : ("(" + viewType + ")") ) + (isFragment ? "rootView." : "") + "findViewById(R.id." + viewId + ");\n");
        }
        initView.append("}");
        clazz.add(factory.createMethodFromText(initView.toString(), clazz));
    }

    void generateInActivity(PsiClass clazz, String layoutXml, PsiElementFactory factory){
        PsiMethod[] methods = clazz.findMethodsByName("onCreate", false);
        if(methods.length == 0){
            StringBuilder onCreate = new StringBuilder();
            onCreate.append("@Override protected void onCreate(android.os.Bundle savedInstanceState) {\n");
            onCreate.append("super.onCreate(savedInstanceState);\n");
            onCreate.append("setContentView(" + layoutXml + ");\n");
            onCreate.append("initView()\n");
            onCreate.append("}");
            clazz.add(factory.createMethodFromText(onCreate.toString(), clazz));
        }else{
            boolean hasSetContentView = false;
            for(PsiStatement statement : methods[0].getBody().getStatements()){
                if(statement.getFirstChild() instanceof PsiMethodCallExpression){
                    PsiReferenceExpression callExpression = ((PsiMethodCallExpression) statement.getFirstChild()).getMethodExpression();
                    if(callExpression.getText().contentEquals("setContentView")){
                        hasSetContentView = true;
                        methods[0].getBody().addAfter(factory.createStatementFromText("initView();\n",clazz),statement);
                        break;
                    }
                }
            }
            if(!hasSetContentView){
                methods[0].getBody().addAfter(factory.createStatementFromText("setContentView(" + layoutXml + ");\n", clazz), methods[0].getBody().getLastBodyElement());
                methods[0].getBody().addAfter(factory.createStatementFromText("initView();\n", clazz), methods[0].getBody().getLastBodyElement());
            }
        }
    }

    void generateInFragment(PsiClass clazz, String layoutXml, PsiElementFactory factory){
        PsiMethod[] methods = clazz.findMethodsByName("onCreateView", false);
        if(methods.length == 0){
            StringBuilder onCreateView = new StringBuilder();
            onCreateView.append("@Override public View onCreateView(android.view.LayoutInflater inflater, android.view.ViewGroup container, android.os.Bundle savedInstanceState) {\n");
            onCreateView.append("View rootView = inflater.inflate(" + layoutXml + ", null);\n");
            onCreateView.append("initView(" + "rootView" + ")\n");
            onCreateView.append("}");
            clazz.add(factory.createMethodFromText(onCreateView.toString(), clazz));
        }else{
            boolean isInflated = false;
            for(PsiStatement statement : methods[0].getBody().getStatements()){
                if(statement instanceof PsiExpressionStatement || statement instanceof PsiDeclarationStatement){
                    if(statement.getText().contains("inflate(")){
                        isInflated = true;
                        String[] expresses = statement.getText().split("=")[0].trim().split(" ");
                        String var = null;
                        if(expresses.length > 1){
                            var = expresses[1];
                        }else{
                            var = expresses[0];
                        }
                        methods[0].getBody().addAfter(factory.createStatementFromText("initView(" + var + ");\n",clazz),statement);
                        break;
                    }
                }
            }
            if(!isInflated){
                methods[0].getBody().addAfter(factory.createStatementFromText("View rootView = inflater.inflate(" + layoutXml + ", null);\n", clazz), methods[0].getBody().getLastBodyElement());
                methods[0].getBody().addAfter(factory.createStatementFromText("initView(rootView);\n", clazz), methods[0].getBody().getLastBodyElement());
                methods[0].getBody().addAfter(factory.createStatementFromText("return rootView;\n", clazz), methods[0].getBody().getLastBodyElement());
            }
        }
    }


}
