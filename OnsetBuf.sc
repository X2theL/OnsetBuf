OnsetBuf {

	var <>in;
	var <server;
	var <>defBufLen;
	var <buffer;
	var <list;
	var <synth;
	var <>thr = 0.05;
	var <oscresp;
	var <isRecording = false;

	*new {arg in=0, server, defBufLen=10;
		^super.newCopyArgs(in, server, defBufLen).init;
	}

	init {
		server ?? {server = Server.default};
		list = List.new(30);
	}

	rec {
		if (isRecording.not) {
			if (buffer.isNil) {
				buffer = Buffer.alloc(
					server,
					server.sampleRate * defBufLen,
					2,
					{arg bfr; synth = Synth('onset-buf-rec', ['buf', bfr, 'in', in, 'thr', thr])}
				)
			} {
				if (list.size > 0) { list.clear };
				synth = Synth('onset-buf-rec', ['buf', buffer, 'in', in, 'thr', thr]);
			};

			this.pr_addOSC;
			isRecording = true;
		}
	}

	stopRec {
		if (isRecording) {
			synth.release;
			oscresp.free;
			isRecording = false;
		}
	}

	// return last n onset positions
	getLast { arg num=1;
		^list.copyRange(list.size - num, list.size - 1)
	}

	pr_addOSC {
		oscresp = OSCFunc({arg msg;
			if ( list.last.notNil and: {msg[3] < list.last}) {list.clear}; // if buffer wraps clear list
			list.add(msg[3]);
			msg.postln;
		}, '\tr', server.addr, nil, [synth.nodeID]);
	}

	free {
		this.stopRec;
		buffer.free;
		list = nil;
	}

	// reads a file into a buffer and analyzes it
	read {arg path;
		buffer = Buffer.read(
			server,
			Document.standardizePath(path),
			action: {arg bf;
				synth = Synth('offline-analyze', ['buf', bf, 'thr', 0.05]);
				"analyzing buffer".postln;
				this.pr_addOSC;
				OSCFunc({
					"done analyzing!".postln;
					oscresp.free;
				}, '/n_end', server.addr, nil, [synth.nodeID]).oneShot;
		});

	}

	*initClass {
		ServerBoot.add({
			SynthDef('onset-buf-rec', {arg buf, in=0, thr=0.05, gate=1;
				var env, sig, amp, trig, phase;

				env = EnvGen.ar(Env.asr(0.1), gate, doneAction:2); // global gate
				sig = SoundIn.ar([in, in+1]) * gate;

				phase = Phasor.ar(end:BufFrames.kr(buf));
				BufWr.ar(sig, buf, phase, 0);

				SendTrig.kr(
					Coyote.kr(Mix.new(sig),
						fastMul:0.6,
						thresh:thr),
					99,
					phase
				);
			}).add;
			SynthDef('offline-analyze', {arg buf, thr=0.05;
				var sig, phase;

				phase = Phasor.ar(end:BufFrames.kr(buf));
				sig = FreeSelfWhenDone.kr(BufRd.ar(2, buf, phase, 0));

				SendTrig.kr(
					Coyote.kr(Mix.new(sig),
						fastMul:0.6,
						thresh:thr),
					99,
					phase
				);
			}).add;
		});
	}
}

AbstractOBP {

	var <>obj;
	var <server, <>out, <group;
	var <>defaultQuant;
	var <durs, globalAmp, <proxy;

	play {arg clock, quant=0;
		clock = clock ?? {TempoClock.default};
		proxy.play(clock, quant:quant);
	}

	stop {
		proxy.stop;
	}

	hush {
		globalAmp.source = 0.0;
		group.set('amp', 0.0);
	}

	on {
		globalAmp.source = 1.0;
		group.set('amp', 1.0);
	}

	free {
		this.stop;
		group.free;
	}
}

LoopOBP : AbstractOBP {

	var <>numBars;

	*new {arg obj, numBars=1, defaultQuant=4, server, out=0;
		^super.new.obj_(obj).numBars_(numBars).defaultQuant_(defaultQuant).out_(out).init(server);
	}

	init {arg srv;
		srv ?? {server = Server.default};
		group = Group.new;
		globalAmp = PatternProxy(1.0);
		proxy = EventPatternProxy( Pbind (
			'instrument', 'simpleplayer',
			'group', group,
			'out', out,
			'dur', defaultQuant * numBars,
			'legato', 1,
			'amp', globalAmp,
			'buf', obj.buffer
		)).quant_(defaultQuant);
	}

	*initClass {
		ServerBoot.add({
			// Unexpected results, unless recorded portions are longer than dur
			SynthDef('simpleplayer', {arg buf, pos=0, gate=1, amp=1.0, out=0;
				var env, sig, phase;
				env = EnvGen.ar(Env.asr(0.01), gate, doneAction:2);
				phase = Phasor.ar(start:pos, end:BufFrames.kr(buf));
				sig = BufRd.ar(2, buf, phase, loop:0) * env * Lag.kr(amp, 0.04);
				Out.ar(out, sig);
			}).add;
		})
	}
}

GrainOBP : AbstractOBP {

	var <>teiler;  // 1/teiler=dur. Default 16th notes
	var <decays, <pos, <amps;

	*new {arg obj, teiler=4, defaultQuant=4, server, out=0;
		^super.new.obj_(obj).teiler_(teiler).defaultQuant_(defaultQuant).out_(out).init(server);
	}

	init { arg srv;
		srv ?? {server = Server.default};
		group = Group.new;
		globalAmp = PatternProxy(1.0);
		amps = PatternProxy(Pseq(1.0.dup(obj.list.size), inf));
		amps.quant = defaultQuant; // so the changes apply at next "bar"

		durs = PatternProxy(1/teiler);
		durs.quant = defaultQuant; // changes apply at next bar
		pos = PatternProxy(Pseq(obj.list, inf));
		decays = PatternProxy(0.1);
		proxy = EventPatternProxy( Pbind (
				'instrument', 'grainplayer',
				'group', group,
				'out', out,
				'dur', durs,
				'amp', amps * globalAmp,
				'buf', obj.buffer,
				'pos', pos,
				'dec', decays
		)).quant_(defaultQuant);
	}

		// make a rhythm. Only works if grain is playing
	rh {arg numBeats ... args;
		var arr;
		args = ((args - 1) * teiler).asFloat;
		arr = Array.fill(teiler * numBeats, {arg i;
			if (args.includes(i.asFloat)) {
				1.0
			} {
				0.0
			};
		});
		this.setAmps(arr);
		^arr; // return new array
	}

	// synchronise pos with amps to create sequence that repeats exactly
	sync {
		if (pos.source.list.size < amps.source.list.size) {
			pos.source.list = pos.source.list.wrapExtend(amps.source.list.size);
		} {
			if (pos.source.list.size > amps.source.list.size) {
				pos.source.list = pos.source.list.copyRange(0, amps.source.list.size -1);
			}
		}
	}

	// resets the pos array to original
	unsync {
		pos.source.list = obj.list;
	}

	// shuffles the pos array and returns the new array
	shuf {
		var arr = pos.source.list.scramble;
		this.setPos(arr);
		^arr;
	}

	setPos {arg arr;
		pos.source = Pseq(arr, inf);
	}

	setAmps {arg arr;
		amps.source = Pseq(arr, inf); // set the source of PatternProxy
	}

	dec {arg pat=0.1;
		decays.source = pat;
	}

		*initClass {
		ServerBoot.add({
			// grain player with auto release
			SynthDef('grainplayer', {arg buf, pos=0, att=0.01, dec=0.3, amp=1.0, out=0;
				var env, sig, phase;
				env = EnvGen.ar(Env.perc(att, dec), 1, doneAction:2);
				phase = Phasor.ar(start:pos, end:BufFrames.kr(buf));
				sig = BufRd.ar(2, buf, phase, loop:0) * env * Lag.kr(amp);
				Out.ar(out, sig);
			}).add;
		});
	}
}