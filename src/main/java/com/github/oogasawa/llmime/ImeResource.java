package com.github.oogasawa.llmime;

import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * REST endpoint for IME conversion.
 *
 * POST /api/convert
 * { "input": "かんじへんかん", "context": "optional preceding text" }
 * => { "output": "漢字変換" }
 */
@Path("/api")
public class ImeResource {

    @Inject
    ConversionService conversionService;

    @POST
    @Path("/convert")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public ConvertResponse convert(ConvertRequest request) {
        int n = request.n() != null ? request.n() : 3;
        var candidates = conversionService.convertCandidates(request.input(), request.context(), n);
        String output = candidates.isEmpty() ? request.input() : candidates.get(0);
        return new ConvertResponse(output, candidates);
    }

    @POST
    @Path("/complete")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public CompleteResponse complete(CompleteRequest request) {
        String result = conversionService.complete(request.context(), request.partial());
        return new CompleteResponse(result);
    }

    @POST
    @Path("/segment")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public SegmentResponse segment(SegmentRequest request) {
        var segments = conversionService.segment(request.input(), request.context());
        return new SegmentResponse(segments);
    }

    @POST
    @Path("/candidates")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public CandidatesResponse candidates(CandidatesRequest request) {
        var result = conversionService.candidates(request.reading(), request.context());
        return new CandidatesResponse(result);
    }

    @POST
    @Path("/convert-multi")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public MultiResponse convertMulti(MultiConvertRequest request) {
        int n = request.n() != null ? request.n() : 5;
        var results = conversionService.convertMulti(request.input(), request.context(), n);
        return new MultiResponse(results);
    }

    @POST
    @Path("/complete-multi")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public MultiResponse completeMulti(MultiCompleteRequest request) {
        int n = request.n() != null ? request.n() : 3;
        var results = conversionService.completeMulti(request.context(), request.partial(), n);
        return new MultiResponse(results);
    }

    @Inject
    MozcClient mozcClient;

    /**
     * Segment-aware conversion: splits input into bunsetsu using Mozc.
     * Returns Mozc-only candidates for fast initial display.
     * Use /api/segment-candidates to fetch full (Mozc+LLM) candidates per segment.
     */
    @POST
    @Path("/segment-convert")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public SegmentConvertResponse segmentConvert(SegmentConvertRequest request) {
        var mozcSegs = mozcClient.getSegments(request.input());
        var result = new java.util.ArrayList<SegmentWithCandidates>();
        for (var seg : mozcSegs) {
            // Use Mozc candidates directly (fast) — no LLM call
            var candidates = new java.util.ArrayList<>(seg.candidates());
            if (candidates.isEmpty()) {
                candidates.add(seg.reading());
            }
            result.add(new SegmentWithCandidates(seg.reading(), candidates));
        }
        return new SegmentConvertResponse(result);
    }

    /**
     * Get candidates for a single segment reading (Mozc + LLM merged).
     */
    @POST
    @Path("/segment-candidates")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public MultiResponse segmentCandidates(MultiConvertRequest request) {
        int n = request.n() != null ? request.n() : 5;
        var results = conversionService.convertMulti(request.input(), request.context(), n);
        return new MultiResponse(results);
    }

    @POST
    @Path("/record")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public RecordResponse record(RecordRequest request) {
        conversionService.recordConversion(request.input(), request.output());
        return new RecordResponse("ok");
    }

    public record ConvertRequest(String input, String context, Integer n) {}
    public record ConvertResponse(String output, List<String> candidates) {}
    public record CompleteRequest(String context, String partial) {}
    public record CompleteResponse(String completion) {}
    public record MultiConvertRequest(String input, String context, Integer n) {}
    public record MultiCompleteRequest(String context, String partial, Integer n) {}
    public record MultiResponse(List<String> candidates) {}
    public record SegmentRequest(String input, String context) {}
    public record SegmentResponse(List<ConversionService.Segment> segments) {}
    public record CandidatesRequest(String reading, String context) {}
    public record CandidatesResponse(List<String> candidates) {}
    public record SegmentConvertRequest(String input, String context, Integer n) {}
    public record SegmentWithCandidates(String reading, List<String> candidates) {}
    public record SegmentConvertResponse(List<SegmentWithCandidates> segments) {}
    public record RecordRequest(String input, String output) {}
    public record RecordResponse(String status) {}
}
