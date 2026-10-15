package com.code.pal.agent.contract;
import com.code.pal.agent.llm.*;
import com.code.pal.agent.route.*;
import com.code.pal.project.capability.BuildDecisions;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Fake unit data exported after production coordinator/ownership/merge; real package/API gate runs separately. */
class StructuredFixtureExportTest {
    @Test void sequentialViteAndMernProducePreservedInputsForThePinnedBehaviorRunner() throws Exception {
        for(var c:List.of(ContractFixtures.vite(),ContractFixtures.mern())){
            var f=new ContractCoordinatorTest();f.project.write("user-notes.txt","existing user file");
            var run=f.run(ProjectType.BUILD);var ctx=f.context(run);
            when(f.store.forRun(f.project.projectId,run.getId())).thenReturn(new ContractStore.Stored(run.getId(),f.project.projectId,c,"hash",f.binding,"APPROVED","BUILD_REQUEST"));
            when(f.decisions.state(f.project.projectId)).thenReturn(new BuildDecisions.State(new BuildDecisions.Choice(1,c.storage(),c.auth(),null),new BuildDecisions.Available(true,null),null));
            doAnswer(call->{f.changes.recordProjectValidation(ctx,true,List.of());var checks=new ArrayList<com.code.pal.agent.validation.ValidationCheck>();checks.add(new com.code.pal.agent.validation.ValidationCheck("RUNTIME",com.code.pal.agent.validation.ValidationCheck.Outcome.PASS,true,"Fixture export only"));c.acceptance().api().forEach(a->checks.add(new com.code.pal.agent.validation.ValidationCheck("API_"+a.id(),com.code.pal.agent.validation.ValidationCheck.Outcome.PASS,true,"Real generated API checked separately")));return f.report(true,checks);}).when(f.validation).validateAll(any());
            var count=new AtomicInteger();f.coordinator.execute(ctx,run,RunSize.of(SizeTier.M,List.of(),f.props),"Build items","fixture",request->{
                count.incrementAndGet();var input=f.mapper.readValue(request.messages().get(1).content(),UnitGenerator.Input.class);
                String path=input.unit().ownedPaths().getFirst();String code=path.endsWith("items.ts")?router(c):"import './rendra/"+c.id()+"/theme.css'\nexport default function App() { return <main><h1>Items</h1></main> }\n";
                return new LlmClient.LlmResult(f.mapper.writeValueAsString(new UnitGenerator.Output(List.of(new UnitGenerator.Delta(path,input.snapshot().get(path).version(),code)))),null,List.of(),new LlmClient.Usage(100,200),"stop");
            },()->{},title->{});
            assertEquals(c.units().size(),count.get());assertEquals("existing user file",f.project.read("user-notes.txt"));
            Path target=Path.of("target/codex-structured-app-fixtures",c.stack().name().toLowerCase(Locale.ROOT)).toAbsolutePath();
            for(var entry:f.project.files.list(f.project.projectId,ctx.root(),12,Set.of(".codepal","node_modules"))){if(entry.directory())continue;Path dest=target.resolve(entry.path()).normalize();assertTrue(dest.startsWith(target));Files.createDirectories(dest.getParent());Files.write(dest,f.project.files.readBytes(f.project.projectId,ctx.root(),entry.path(),1_000_000));}
            if(c.stack()==BuildContract.Stack.MERN)for(String helper:List.of("auth.ts","ownership.ts"))try(var in=getClass().getClassLoader().getResourceAsStream("contract/"+helper)){Files.write(target.resolve(c.serverRoot()+helper),in.readAllBytes());}
        }
    }
    private String router(BuildContract c){return """
            import { Router } from 'express'
            import { models } from './rendra/CONTRACT/models.js'
            export const itemsRouter = Router()
            const Item = models.Item
            function item(row: any) { return {id:String(row._id), value:row.value} }
            itemsRouter.post('/',async(req,res)=>{const row=await Item.create({value:String(req.body.value)});res.status(201).json(item(row))})
            itemsRouter.get('/:id',async(req,res)=>{const row=await Item.findById(req.params.id);if(!row){res.status(404).json({error:'Missing'});return};res.json(item(row))})
            itemsRouter.put('/:id',async(req,res)=>{const row=await Item.findByIdAndUpdate(req.params.id,{value:String(req.body.value)},{new:true});if(!row){res.status(404).json({error:'Missing'});return};res.json(item(row))})
            itemsRouter.delete('/:id',async(req,res)=>{const row=await Item.findByIdAndDelete(req.params.id);if(!row){res.status(404).json({error:'Missing'});return};res.json(item(row))})
            """.replace("CONTRACT",c.id().toString());}
}
