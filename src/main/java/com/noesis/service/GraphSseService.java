package com.noesis.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
public class GraphSseService {

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter createEmitter() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    public void broadcastGraphUpdate(int totalNodes, int totalEdges, int nodesAdded, int edgesAdded) {
        List<SseEmitter> dead = new java.util.ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                    .name("graph-update")
                    .data("{\"totalNodes\":" + totalNodes + ",\"totalEdges\":" + totalEdges
                        + ",\"nodesAdded\":" + nodesAdded + ",\"edgesAdded\":" + edgesAdded + "}"));
            } catch (IOException e) {
                dead.add(emitter);
            }
        }
        emitters.removeAll(dead);
    }

    public void broadcastBulkProgress(int filesDiscovered, int filesProcessed, int filesFailed,
                                       int assertionsGenerated, int edgesGenerated, int queueBacklog,
                                       double throughputDocsSec, int etaSeconds) {
        List<SseEmitter> dead = new java.util.ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                    .name("bulk-progress")
                    .data("{\"filesDiscovered\":" + filesDiscovered
                        + ",\"filesProcessed\":" + filesProcessed
                        + ",\"filesFailed\":" + filesFailed
                        + ",\"assertionsGenerated\":" + assertionsGenerated
                        + ",\"edgesGenerated\":" + edgesGenerated
                        + ",\"queueBacklog\":" + queueBacklog
                        + ",\"throughputDocsSec\":" + throughputDocsSec
                        + ",\"etaSeconds\":" + etaSeconds + "}"));
            } catch (IOException e) {
                dead.add(emitter);
            }
        }
        emitters.removeAll(dead);
    }

    public void broadcastWorkerUpdate(List<?> workers) {
        List<SseEmitter> dead = new java.util.ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                    .name("worker-update")
                    .data(workers));
            } catch (IOException e) {
                dead.add(emitter);
            }
        }
        emitters.removeAll(dead);
    }
}
