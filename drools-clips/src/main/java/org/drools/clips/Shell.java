/**
 * 
 */
package org.drools.clips;

import java.io.PrintStream;
import java.io.Reader;
import java.io.Serializable;
import java.io.StringReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

import org.antlr.runtime.ANTLRReaderStream;
import org.antlr.runtime.CommonTokenStream;
import org.drools.FactHandle;
import org.drools.RuleBase;
import org.drools.RuleBaseFactory;
import org.drools.StatefulSession;
import org.drools.base.ClassTypeResolver;
import org.drools.base.mvel.DroolsMVELFactory;
import org.drools.common.InternalRuleBase;
import org.drools.compiler.PackageBuilder;
import org.drools.lang.descr.AttributeDescr;
import org.drools.lang.descr.FunctionDescr;
import org.drools.lang.descr.ImportDescr;
import org.drools.lang.descr.PackageDescr;
import org.drools.lang.descr.RuleDescr;
import org.drools.spi.GlobalResolver;
import org.mvel.MVEL;
import org.mvel.ParserContext;
import org.mvel.ast.Function;
import org.mvel.compiler.CompiledExpression;
import org.mvel.compiler.ExpressionCompiler;
import org.mvel.util.CompilerTools;

public class Shell
    implements
    ParserHandler,
    VariableContext,
    FunctionContext,
    PrintRouterContext {
    private Map<String, Object> vars;

    private RuleBase            ruleBase;
    private StatefulSession     session;

    // private Map                 functions;

    private Map                 directImports;
    private Set                 dynamicImports;

    private ClassTypeResolver   typeResolver;

    private String              moduleName;
    private static final String MAIN = "MAIN";

    private DroolsMVELFactory   factory;

    public Shell() {
        this( RuleBaseFactory.newRuleBase() );
    }

    public Shell(RuleBase ruleBase) {
        this.moduleName = MAIN;
        this.ruleBase = ruleBase;
        this.session = this.ruleBase.newStatefulSession();
        // this.functions = new HashMap();
        this.directImports = new HashMap();
        this.dynamicImports = new HashSet();

        this.typeResolver = new ClassTypeResolver( new HashSet(),
                                                   ((InternalRuleBase) this.ruleBase).getConfiguration().getClassLoader() );

        this.factory = (DroolsMVELFactory) new DroolsMVELFactory( null,
                                                                  null,
                                                                  ((InternalRuleBase) this.ruleBase).getGlobals() );

        this.vars = new HashMap<String, Object>();
        GlobalResolver2 globalResolver = new GlobalResolver2( this.vars,
                                                              this.session.getGlobalResolver() );
        this.session.setGlobalResolver( globalResolver );

        this.factory.setContext( null,
                                 null,
                                 null,
                                 this.session,
                                 this.vars );

        addRouter( "t",
                   System.out );
    }
    
    public StatefulSession getStatefulSession() {
        return this.session;
    }

    public static class GlobalResolver2
        implements
        GlobalResolver {
        private Map<String, Object> vars;
        private GlobalResolver      resolver;

        public GlobalResolver2(Map<String, Object> vars,
                               GlobalResolver resolver) {
            this.vars = vars;
            this.resolver = resolver;
        }

        public Object resolveGlobal(String identifier) {
            Object object = this.vars.get( identifier );
            if ( object == null ) {
                object = resolver.resolveGlobal( identifier );
            }
            return object;
        }

        public void setGlobal(String identifier,
                              Object value) {
            this.resolver.setGlobal( identifier,
                                     value );

        }
    }

    public void functionHandler(FunctionDescr functionDescr) {
        Appendable builder = new StringBuilderAppendable();

        // strip lead/trailing quotes
        String name = functionDescr.getName().trim();
        if ( name.charAt( 0 ) == '"' ) {
            name = name.substring( 1 );
        }

        if ( name.charAt( name.length() - 1 ) == '"' ) {
            name = name.substring( 0,
                                   name.length() - 1 );
        }
        builder.append( "function " + name + "(" );

        for ( int i = 0, length = functionDescr.getParameterNames().size(); i < length; i++ ) {
            builder.append( functionDescr.getParameterNames().get( i ) );
            if ( i < length - 1 ) {
                builder.append( ", " );
            }
        }

        builder.append( ") {\n" );
        List list = (List) functionDescr.getContent();
        for ( Iterator it = list.iterator(); it.hasNext(); ) {
            FunctionHandlers.dump( (LispForm) it.next(),
                                   builder );
        }
        builder.append( "}" );

        ExpressionCompiler compiler = new ExpressionCompiler( builder.toString() );
        Serializable s1 = compiler.compile();
        Map<String, org.mvel.ast.Function> map = CompilerTools.extractAllDeclaredFunctions( (CompiledExpression) s1 );
        for ( org.mvel.ast.Function function : map.values() ) {
            addFunction( function );
        }

    }

    public void importHandler(ImportDescr descr) {
        String importText = descr.getTarget().trim();

        this.typeResolver.addImport( descr.getTarget() );

        if ( importText.endsWith( "*" ) ) {
            this.dynamicImports.add( importText );
        } else {
            Class cls;
            try {
                cls = this.typeResolver.resolveType( importText );
            } catch ( ClassNotFoundException e ) {
                throw new RuntimeException( "Unable to resolve : " + importText );
            }
            this.directImports.put( cls.getSimpleName(),
                                    cls );
        }
    }

    public void lispFormHandler(LispForm lispForm) {
        StringBuilderAppendable appendable = new StringBuilderAppendable();
        FunctionHandlers.dump( lispForm,
                               appendable );

        ParserContext context = new ParserContext();

        for ( Iterator it = this.directImports.entrySet().iterator(); it.hasNext(); ) {
            Entry entry = (Entry) it.next();
            context.addImport( (String) entry.getKey(),
                               (Class) entry.getValue() );
        }

        for ( Iterator it = this.dynamicImports.iterator(); it.hasNext(); ) {
            String importText = ((String) it.next()).trim();
            context.addPackageImport( importText.substring( 0,
                                                            importText.length() - 2 ) );
        }

        ExpressionCompiler expr = new ExpressionCompiler( appendable.toString() );
        Serializable executable = expr.compile( context );

        MVEL.executeExpression( executable,
                                this,
                                this.factory );

    }

    public void ruleHandler(RuleDescr ruleDescr) {
        String module = getModuleName( ruleDescr.getAttributes() );

        PackageDescr pkg = createPackageDescr( module );
        pkg.addRule( ruleDescr );

        PackageBuilder builder = new PackageBuilder();
        builder.addPackage( pkg );

        try {
            this.ruleBase.addPackage( builder.getPackage() );
        } catch ( Exception e ) {
            e.printStackTrace();
        }
    }

    public String getModuleName(List list) {
        for ( Iterator it = list.iterator(); it.hasNext(); ) {
            AttributeDescr attr = (AttributeDescr) it.next();
            if ( attr.getName().equals( "agenda-group" ) ) {
                return attr.getValue();
            }
        }
        return "MAIN";
    }

    public void eval(String string) {
        eval( new StringReader( string ) );
    }

    public void eval(Reader reader) {
        ClipsParser parser;
        try {
            parser = new ClipsParser( new CommonTokenStream( new ClipsLexer( new ANTLRReaderStream( reader ) ) ) );
            parser.eval( this );
        } catch ( Exception e ) {
            e.printStackTrace();
        }
    }

    public void run() {
        this.session.fireAllRules();
    }

    public void run(int fireLimit) {
        this.session.fireAllRules( fireLimit );
    }

    public FactHandle insert(Object object) {
        return this.session.insert( object );
    }

    public void importEntry(String importEntry) {

    }

    public void addFunction(Function function) {
        this.factory.createVariable( function.getAbsoluteName(),
                                     function );
    }

    public boolean removeFunction(String functionName) {
        return false; //(this.vars.remove( functionName ) != null);
    }

    public Map<String, Function> getFunctions() {
        Map<String, Function> map = new HashMap<String, Function>();
        //        for ( Iterator it = this.vars.entrySet().iterator(); it.hasNext(); ) {
        //            Entry entry = (Entry) it.next();
        //            if ( entry.getValue() instanceof Function ) {
        //                map.put( (String) entry.getKey(),
        //                         (Function) entry.getValue() );
        //            }
        //        }
        return map;
    }

    public void addRouter(String name,
                          PrintStream out) {

        Map routers = (Map) this.vars.get( "printrouters" );
        if ( routers == null ) {
            routers = new HashMap();
            this.factory.createVariable( "printrouters",
                                         routers );
        }

        routers.put( name,
                     out );

    }

    public boolean removeRouter(String name) {
        return false; //(this.vars.remove( name ) != null);
    }

    //    public Map<String, PrintStream> getRouters() {
    //        Map<String, PrintStream> map = new HashMap<String, PrintStream>();
    //        for ( Iterator it = this.vars.entrySet().iterator(); it.hasNext(); ) {
    //            Entry entry = (Entry) it.next();
    //            if ( entry.getValue() instanceof Function ) {
    //                map.put( (String) entry.getKey(),
    //                         (PrintStream) entry.getValue() );
    //            }
    //        }
    //        return map;
    //    }

    public void addVariable(String name,
                            Object value) {
        if ( name.startsWith( "?" ) ) {
            name = name.substring( 1 );
        }
        this.factory.createVariable( name,
                                     value );
        //        this.session.setGlobal( name,
        //                                value );
    }

    //    public void removeVariable(String name) {
    //        String temp = this.varNameMap.get( name );
    //        if ( temp != null ) {
    //            name = temp;
    //        }
    //        this.session.getGlobal( identifier ).remove( name );
    //    }

    private PackageDescr createPackageDescr(String moduleName) {
        PackageDescr pkg = new PackageDescr( moduleName );

        for ( Iterator it = this.typeResolver.getImports().iterator(); it.hasNext(); ) {
            pkg.addImport( new ImportDescr( (String) it.next() ) );
        }

        return pkg;
    }
}