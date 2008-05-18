package org.drools.marshalling;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

import org.drools.base.ClassObjectType;
import org.drools.common.AgendaItem;
import org.drools.common.BaseNode;
import org.drools.common.BinaryHeapQueueAgendaGroup;
import org.drools.common.DefaultAgenda;
import org.drools.common.DefaultFactHandle;
import org.drools.common.EqualityKey;
import org.drools.common.InternalAgendaGroup;
import org.drools.common.InternalFactHandle;
import org.drools.common.InternalRuleBase;
import org.drools.common.InternalRuleFlowGroup;
import org.drools.common.InternalWorkingMemory;
import org.drools.common.PropagationContextImpl;
import org.drools.common.RuleFlowGroupImpl;
import org.drools.common.TruthMaintenanceSystem;
import org.drools.concurrent.ExecutorService;
import org.drools.reteoo.BetaMemory;
import org.drools.reteoo.BetaNode;
import org.drools.reteoo.EntryPointNode;
import org.drools.reteoo.EvalConditionNode;
import org.drools.reteoo.ExistsNode;
import org.drools.reteoo.InitialFactHandle;
import org.drools.reteoo.InitialFactHandleDummyObject;
import org.drools.reteoo.JoinNode;
import org.drools.reteoo.LeftInputAdapterNode;
import org.drools.reteoo.LeftTuple;
import org.drools.reteoo.LeftTupleSink;
import org.drools.reteoo.LeftTupleSource;
import org.drools.reteoo.NotNode;
import org.drools.reteoo.ObjectSink;
import org.drools.reteoo.ObjectTypeNode;
import org.drools.reteoo.ReteooFactHandleFactory;
import org.drools.reteoo.ReteooStatefulSession;
import org.drools.reteoo.ReteooWorkingMemory;
import org.drools.reteoo.RightTuple;
import org.drools.reteoo.RightTupleSink;
import org.drools.reteoo.RuleTerminalNode;
import org.drools.reteoo.EvalConditionNode.EvalMemory;
import org.drools.reteoo.RuleTerminalNode.TerminalNodeMemory;
import org.drools.rule.EntryPoint;
import org.drools.rule.GroupElement;
import org.drools.rule.Package;
import org.drools.rule.Rule;
import org.drools.spi.Activation;
import org.drools.spi.AgendaGroup;
import org.drools.spi.FactHandleFactory;
import org.drools.spi.ObjectType;
import org.drools.spi.PropagationContext;
import org.drools.spi.RuleFlowGroup;
import org.drools.util.BinaryHeapQueue;
import org.drools.util.Iterator;
import org.drools.util.ObjectHashMap;
import org.drools.util.ObjectHashSet;
import org.drools.util.ObjectHashSet.ObjectEntry;

public class InputMarshaller {
    public static ReteooStatefulSession readSession(MarshallerReaderContext context,
                                                    int id,
                                                    ExecutorService executor) throws IOException,
                                                                             ClassNotFoundException {
        FactHandleFactory handleFactory = context.ruleBase.newFactHandleFactory( context.readInt(),
                                                                                 context.readLong() );

        InitialFactHandle initialFactHandle = new InitialFactHandle( new DefaultFactHandle( context.readInt(), //id
                                                                                            new InitialFactHandleDummyObject(),
                                                                                            context.readLong() ) ); //recency        
        context.handles.put( initialFactHandle.getId(),
                             initialFactHandle );

        long propagationCounter = context.readLong();

        DefaultAgenda agenda = new DefaultAgenda( context.ruleBase, false );
        readAgenda( context, agenda );
        ReteooStatefulSession session = new ReteooStatefulSession( id,
                                                                   context.ruleBase,
                                                                   executor,
                                                                   handleFactory,
                                                                   initialFactHandle,
                                                                   propagationCounter,
                                                                   agenda );
        
        // RuleFlowGroups need to reference the session
        for ( RuleFlowGroup group : agenda.getRuleFlowGroupsMap().values() ) {
            ((RuleFlowGroupImpl) group).setWorkingMemory( session );
        }
        context.wm = session;

        readFactHandles( context );

        readActionQueue( context );

        if ( context.readBoolean() ) {
            readTruthMaintenanceSystem( context );
        }

        return session;
    }
    
    public static void readAgenda(MarshallerReaderContext context, DefaultAgenda agenda) throws IOException {
        ObjectInputStream stream = context.stream;
        while ( stream.readInt() == PersisterEnums.AGENDA_GROUP ) {
            BinaryHeapQueueAgendaGroup group = new BinaryHeapQueueAgendaGroup( stream.readUTF(), context.ruleBase );
            group.setActive( stream.readBoolean() );
            agenda.getAgendaGroupsMap().put( group.getName(), group );            
        }
        
        while ( stream.readInt() == PersisterEnums.AGENDA_GROUP ) {
            String agendaGroupName = stream.readUTF();
            agenda.getStackList().add( agenda.getAgendaGroup( agendaGroupName ) );
        }
        
        while ( stream.readInt() == PersisterEnums.RULE_FLOW_GROUP ) {
            String rfgName = stream.readUTF();
            boolean active = stream.readBoolean();
            boolean autoDeactivate = stream.readBoolean();
            RuleFlowGroup rfg = new RuleFlowGroupImpl( rfgName, active, autoDeactivate );
            agenda.getRuleFlowGroupsMap().put(  rfgName, rfg );
        }        
                
    }

    public static void readActionQueue(MarshallerReaderContext context) throws IOException {
        ReteooWorkingMemory wm = (ReteooWorkingMemory) context.wm;
        Queue actionQueue = wm.getActionQueue();
        while ( context.readInt() == PersisterEnums.WORKING_MEMORY_ACTION ) {
            actionQueue.offer( PersisterHelper.readWorkingMemoryAction( context ) );
        }
    }

    public static void readTruthMaintenanceSystem(MarshallerReaderContext context) throws IOException {
        ObjectInputStream stream = context.stream;

        TruthMaintenanceSystem tms = context.wm.getTruthMaintenanceSystem();
        while ( stream.readInt() == PersisterEnums.EQUALITY_KEY ) {
            int status = stream.readInt();
            int factHandleId = stream.readInt();
            InternalFactHandle handle = (InternalFactHandle) context.handles.get( factHandleId );
            EqualityKey key = new EqualityKey( handle,
                                               status );
            handle.setEqualityKey( key );
            while ( stream.readInt() == PersisterEnums.FACT_HANDLE ) {
                factHandleId = stream.readInt();
                handle = (InternalFactHandle) context.handles.get( factHandleId );
                key.addFactHandle( handle );
                handle.setEqualityKey( key );
            }
            tms.put( key );
        }
    }

    public static void readFactHandles(MarshallerReaderContext context) throws IOException,
                                                                        ClassNotFoundException {
        ObjectInputStream stream = context.stream;
        InternalRuleBase ruleBase = context.ruleBase;
        PlaceholderResolverStrategyFactory resolverStrategyFactory = context.resolverStrategyFactory;
        InternalWorkingMemory wm = context.wm;

        if ( stream.readBoolean() ) {
            InternalFactHandle initialFactHandle = wm.getInitialFactHandle();
            int sinkId = stream.readInt();
            ObjectTypeNode initialFactNode = (ObjectTypeNode) context.sinks.get( sinkId );
            ObjectHashSet initialFactMemory = (ObjectHashSet) context.wm.getNodeMemory( initialFactNode );

            initialFactMemory.add( initialFactHandle );
            readRightTuples( initialFactHandle,
                             context );
        }

        int size = stream.readInt();

        // load the handles
        InternalFactHandle[] handles = new InternalFactHandle[size];
        for ( int i = 0; i < size; i++ ) {
            int id = stream.readInt();
            long recency = stream.readLong();
            
            int strategyIndex = stream.readInt();
            PlaceholderResolverStrategy strategy = resolverStrategyFactory.getStrategy( strategyIndex );
            ObjectPlaceholder placeHolder = strategy.read( stream );

            Object object = placeHolder.resolveObject();

            InternalFactHandle handle = new DefaultFactHandle( id,
                                                               object,
                                                               recency );
            context.handles.put( id,
                                 handle );
            handles[i] = handle;

            context.wm.getObjectStore().addHandle( handle,
                                                   object );

            readRightTuples( handle,
                             context );
        }

        EntryPointNode node = ruleBase.getRete().getEntryPointNode( EntryPoint.DEFAULT );
        Map<ObjectType, ObjectTypeNode> objectTypeNodes = node.getObjectTypeNodes();

        // add handles to object type nodes
        for ( InternalFactHandle handle : handles ) {
            Object object = handle.getObject();
            ClassObjectType objectType = new ClassObjectType( object.getClass() );
            ObjectTypeNode objectTypeNode = objectTypeNodes.get( objectType );
            ObjectHashSet set = (ObjectHashSet) context.wm.getNodeMemory( objectTypeNode );
            set.add( handle,
                     false );
        }

        InternalFactHandle handle = wm.getInitialFactHandle();
        while ( stream.readInt() == PersisterEnums.LEFT_TUPLE ) {
            LeftTupleSink sink = (LeftTupleSink) context.sinks.get( stream.readInt() );
            LeftTuple leftTuple = new LeftTuple( handle,
                                                 sink,
                                                 true );
            readLeftTuple( leftTuple,
                           context );
        }

        readLeftTuples( context );

        readPropagationContexts( context );

        readActivations( context );
    }

    public static void readRightTuples(InternalFactHandle factHandle,
                                       MarshallerReaderContext context) throws IOException {
        ObjectInputStream stream = context.stream;
        while ( stream.readInt() == PersisterEnums.RIGHT_TUPLE ) {
            readRightTuple( context,
                            factHandle );
        }
    }

    public static void readRightTuple(MarshallerReaderContext context,
                                      InternalFactHandle factHandle) throws IOException {
        ObjectInputStream stream = context.stream;

        RightTupleSink sink = (RightTupleSink) context.sinks.get( stream.readInt() );

        BetaMemory memory = (BetaMemory) context.wm.getNodeMemory( (BetaNode) sink );

        RightTuple rightTuple = new RightTuple( factHandle,
                                                sink );
        context.rightTuples.put( new RightTupleKey( factHandle.getId(),
                                                    sink ),
                                 rightTuple );

        memory.getRightTupleMemory().add( rightTuple );
    }

    public static void readLeftTuples(MarshallerReaderContext context) throws IOException {
        ObjectInputStream stream = context.stream;

        while ( stream.readInt() == PersisterEnums.LEFT_TUPLE ) {
            LeftTupleSink sink = (LeftTupleSink) context.sinks.get( stream.readInt() );
            int factHandleId = stream.readInt();
            LeftTuple leftTuple = new LeftTuple( context.handles.get( factHandleId ),
                                                 sink,
                                                 true );
            readLeftTuple( leftTuple,
                           context );
        }
    }

    public static void readLeftTuple(LeftTuple parentLeftTuple,
                                     MarshallerReaderContext context) throws IOException {
        ObjectInputStream stream = context.stream;
        InternalRuleBase ruleBase = context.ruleBase;
        InternalWorkingMemory wm = context.wm;
        Map<Integer, BaseNode> sinks = context.sinks;

        LeftTupleSink sink = parentLeftTuple.getLeftTupleSink();

        if ( sink instanceof JoinNode ) {
            BetaMemory memory = (BetaMemory) context.wm.getNodeMemory( (BetaNode) sink );
            memory.getLeftTupleMemory().add( parentLeftTuple );

            while ( stream.readInt() == PersisterEnums.RIGHT_TUPLE ) {
                LeftTupleSink childSink = (LeftTupleSink) sinks.get( stream.readInt() );
                int factHandleId = stream.readInt();
                RightTupleKey key = new RightTupleKey( factHandleId,
                                                       sink );
                RightTuple rightTuple = context.rightTuples.get( key );
                LeftTuple childLeftTuple = new LeftTuple( parentLeftTuple,
                                                          rightTuple,
                                                          childSink,
                                                          true );
                readLeftTuple( childLeftTuple,
                               context );
            }

        } else if ( sink instanceof EvalConditionNode ) {
            final EvalMemory memory = (EvalMemory) context.wm.getNodeMemory( (EvalConditionNode) sink );
            memory.tupleMemory.add( parentLeftTuple );
            while ( stream.readInt() == PersisterEnums.LEFT_TUPLE ) {
                LeftTupleSink childSink = (LeftTupleSink) sinks.get( stream.readInt() );
                LeftTuple childLeftTuple = new LeftTuple( parentLeftTuple,
                                                          childSink,
                                                          true );   
                readLeftTuple( childLeftTuple,
                               context );                
            }                                  
        } else if ( sink instanceof NotNode ) {
            BetaMemory memory = (BetaMemory) context.wm.getNodeMemory( (BetaNode) sink );
            int type = stream.readInt();
            if ( type == PersisterEnums.LEFT_TUPLE_NOT_BLOCKED ) {
                memory.getLeftTupleMemory().add( parentLeftTuple );

                while ( stream.readInt() == PersisterEnums.LEFT_TUPLE ) {
                    LeftTupleSink childSink = (LeftTupleSink) sinks.get( stream.readInt() );
                    LeftTuple childLeftTuple = new LeftTuple( parentLeftTuple,
                                                              childSink,
                                                              true );
                    readLeftTuple( childLeftTuple,
                                   context );
                }

            } else {
                int factHandleId = stream.readInt();
                RightTupleKey key = new RightTupleKey( factHandleId,
                                                       sink );
                RightTuple rightTuple = context.rightTuples.get( key );

                parentLeftTuple.setBlocker( rightTuple );
                rightTuple.setBlocked( parentLeftTuple );
            }
        } else if ( sink instanceof ExistsNode ) {
            BetaMemory memory = (BetaMemory) context.wm.getNodeMemory( (BetaNode) sink );
            int type = stream.readInt();
            if ( type == PersisterEnums.LEFT_TUPLE_NOT_BLOCKED ) {
                memory.getLeftTupleMemory().add( parentLeftTuple );
            } else {                
                int factHandleId = stream.readInt();
                RightTupleKey key = new RightTupleKey( factHandleId,
                                                       sink );
                RightTuple rightTuple = context.rightTuples.get( key );

                parentLeftTuple.setBlocker( rightTuple );
                rightTuple.setBlocked( parentLeftTuple );                    

                while ( stream.readInt() == PersisterEnums.LEFT_TUPLE ) {
                    LeftTupleSink childSink = (LeftTupleSink) sinks.get( stream.readInt() );
                    LeftTuple childLeftTuple = new LeftTuple( parentLeftTuple,
                                                              childSink,
                                                              true );
                    readLeftTuple( childLeftTuple,
                                   context );
                }
            }
        } else if ( sink instanceof RuleTerminalNode ) {
            RuleTerminalNode ruleTerminalNode = (RuleTerminalNode) sink;
            TerminalNodeMemory memory = (TerminalNodeMemory) wm.getNodeMemory( ruleTerminalNode );
            memory.getTupleMemory().add( parentLeftTuple );

            int pos = context.terminalTupleMap.size();
            context.terminalTupleMap.put( pos,
                                          parentLeftTuple );
        }
    }

    public static void readActivations(MarshallerReaderContext context) throws IOException {
        ObjectInputStream stream = context.stream;

        while ( stream.readInt() == PersisterEnums.ACTIVATION ) {
            readActivation( context );
        }
    }

    public static Activation readActivation(MarshallerReaderContext context) throws IOException {
        ObjectInputStream stream = context.stream;
        InternalRuleBase ruleBase = context.ruleBase;
        InternalWorkingMemory wm = context.wm;

        long activationNumber = stream.readLong();

        int pos = stream.readInt();
        LeftTuple leftTuple = context.terminalTupleMap.get( pos );

        int salience = stream.readInt();

        //PropagationContext context,
        String pkgName = stream.readUTF();
        String ruleName = stream.readUTF();
        Package pkg = ruleBase.getPackage( pkgName );
        Rule rule = pkg.getRule( ruleName );

        RuleTerminalNode ruleTerminalNode = (RuleTerminalNode) leftTuple.getLeftTupleSink();
        GroupElement subRule = ruleTerminalNode.getSubRule();

        PropagationContext pc = context.propagationContexts.get( stream.readLong() );

        AgendaItem activation = new AgendaItem( activationNumber,
                                                leftTuple,
                                                salience,
                                                pc,
                                                rule,
                                                subRule );

        leftTuple.setActivation( activation );

        if ( stream.readBoolean() ) {
            String activationGroupName = stream.readUTF();
            wm.getAgenda().getActivationGroup( activationGroupName ).addActivation( activation );
        }

        boolean activated = stream.readBoolean();
        activation.setActivated( activated );

        InternalAgendaGroup agendaGroup;
        if ( rule.getAgendaGroup() == null || rule.getAgendaGroup().equals( "" ) || rule.getAgendaGroup().equals( AgendaGroup.MAIN ) ) {
            // Is the Rule AgendaGroup undefined? If it is use MAIN,
            // which is added to the Agenda by default
            agendaGroup = (InternalAgendaGroup) wm.getAgenda().getAgendaGroup( AgendaGroup.MAIN );
        } else {
            // AgendaGroup is defined, so try and get the AgendaGroup
            // from the Agenda
            agendaGroup = (InternalAgendaGroup) wm.getAgenda().getAgendaGroup( rule.getAgendaGroup() );
        }
        
        activation.setAgendaGroup( agendaGroup );
        
        if ( activated ) {
            if ( rule.getRuleFlowGroup() == null ) {
                agendaGroup.add( activation );
            } else {
                InternalRuleFlowGroup rfg = (InternalRuleFlowGroup) wm.getAgenda().getRuleFlowGroup( rule.getRuleFlowGroup() );
                rfg.addActivation( activation );
            }
        }

        TruthMaintenanceSystem tms = context.wm.getTruthMaintenanceSystem();
        while ( stream.readInt() == PersisterEnums.LOGICAL_DEPENDENCY ) {
            int factHandleId = stream.readInt();
            InternalFactHandle handle = (InternalFactHandle) context.handles.get( factHandleId );
            tms.addLogicalDependency( handle,
                                      activation,
                                      pc,
                                      rule );
        }

        return activation;
    }

    public static void readPropagationContexts(MarshallerReaderContext context) throws IOException {
        ObjectInputStream stream = context.stream;

        while ( stream.readInt() == PersisterEnums.PROPAGATION_CONTEXT ) {
            readPropagationContext( context );
        }

    }

    public static void readPropagationContext(MarshallerReaderContext context) throws IOException {
        ObjectInputStream stream = context.stream;
        InternalRuleBase ruleBase = context.ruleBase;
        InternalWorkingMemory wm = context.wm;

        int type = stream.readInt();

        Rule rule = null;
        if ( stream.readBoolean() ) {
            String pkgName = stream.readUTF();
            String ruleName = stream.readUTF();
            Package pkg = ruleBase.getPackage( pkgName );
            rule = pkg.getRule( ruleName );
        }

        LeftTuple leftTuple = null;
        if ( stream.readBoolean() ) {
            int tuplePos = stream.readInt();
            leftTuple = (LeftTuple) context.terminalTupleMap.get( tuplePos );
        }

        long propagationNumber = stream.readLong();

        int factHandleId = stream.readInt();
        InternalFactHandle factHandle = context.handles.get( factHandleId );

        int activeActivations = stream.readInt();
        int dormantActivations = stream.readInt();
        String entryPointId = stream.readUTF();

        EntryPoint entryPoint = context.entryPoints.get( entryPointId );
        if ( entryPoint == null ) {
            entryPoint = new EntryPoint( entryPointId );
            context.entryPoints.put( entryPointId,
                                     entryPoint );
        }

        PropagationContext pc = new PropagationContextImpl( propagationNumber,
                                                            type,
                                                            rule,
                                                            leftTuple,
                                                            factHandle,
                                                            activeActivations,
                                                            dormantActivations,
                                                            entryPoint );
        context.propagationContexts.put( propagationNumber,
                                         pc );
    }
}