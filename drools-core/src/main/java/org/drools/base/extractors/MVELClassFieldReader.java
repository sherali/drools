/*
 * Copyright 2010 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.drools.base.extractors;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.drools.base.AccessorKey;
import org.drools.base.ValueType;
import org.drools.base.ClassFieldAccessorCache.CacheEntry;
import org.drools.common.InternalWorkingMemory;
import org.drools.spi.InternalReadAccessor;
import org.mvel2.MVEL;
import org.mvel2.ParserContext;
import org.mvel2.compiler.CompiledExpression;
import org.mvel2.compiler.ExecutableStatement;
import org.mvel2.compiler.ExpressionCompiler;

/**
 * A class field extractor that uses MVEL engine to extract the actual value for a given
 * expression. We use MVEL to resolve nested accessor expressions.
 */
public class MVELClassFieldReader extends BaseObjectClassFieldReader {

    private static final long  serialVersionUID = 510l;

    private ExecutableStatement mvelExpression   = null;
    private Map                extractors       = null;

    public MVELClassFieldReader() {
    }

    public MVELClassFieldReader(Class cls,
                                String fieldName,
                                CacheEntry cache) {
        super( -1, // index
               null, //Object.class, // fieldType
               null ); //ValueType.determineValueType( Object.class ) ); // value type
        this.extractors = new HashMap();
        ParserContext context = new ParserContext();
        context.addInput( "this", cls );
        context.setStrongTyping( true );
        
//        if  ( !fieldName.startsWith( "this." ) ) {
//            fieldName = "this." + fieldName;
//        }
        MVEL.COMPILER_OPT_ALLOW_NAKED_METH_CALL = true;
        this.mvelExpression = (ExecutableStatement)MVEL.compileExpression( fieldName, context);
        
        Class returnType = this.mvelExpression.getKnownEgressType();
        setFieldType( returnType );
        setValueType( ValueType.determineValueType( returnType ) );
        

//        Set inputs = compiler.getParserContextState().getInputs().keySet();
//        for ( Iterator it = inputs.iterator(); it.hasNext(); ) {
//            String basefield = (String) it.next();
//            if ( "this".equals( basefield ) ) {
//                continue;
//            }
//            InternalReadAccessor extr = cache.getReadAccessor( new AccessorKey( cls.getName(),
//                                                                                basefield,
//                                                                                AccessorKey.AccessorType.FieldAccessor ),
//                                                               cls );
//            this.extractors.put( basefield,
//                                 extr );
//        }
    }

    //    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
    //        super.readExternal(in);
    //        mvelExpression  = (CompiledExpression)in.readObject();
    //        extractors  = (Map)in.readObject();
    //    }
    //
    //    public void writeExternal(ObjectOutput out) throws IOException {
    //        super.writeExternal(out);
    //        out.writeObject(mvelExpression);
    //        out.writeObject(extractors);
    //    }

    /* (non-Javadoc)
     * @see org.drools.base.extractors.BaseObjectClassFieldExtractor#getValue(java.lang.Object)
     */
    public Object getValue(InternalWorkingMemory workingMemory,
                           Object object) {
//        Map variables = new HashMap();
//        for ( Iterator it = this.extractors.entrySet().iterator(); it.hasNext(); ) {
//            Map.Entry entry = (Map.Entry) it.next();
//            String var = (String) entry.getKey();
//            InternalReadAccessor extr = (InternalReadAccessor) entry.getValue();
//
//            variables.put( var,
//                           extr.getValue( workingMemory,
//                                          object ) );
//        }
        return MVEL.executeExpression( mvelExpression,
                                       object  );
    }

}
