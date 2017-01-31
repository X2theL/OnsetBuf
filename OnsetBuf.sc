OnsetBuf {

	var <>in;
	var <server;
	var <>defBufLen;
	var <buffer;
	var <list;
	var <synth;
	var <oscresp;
	var <isRecording = false;

	*new {arg in=0, server, defBufLen=10;
		^super.newCopyArgs(in, server, defBufLen).init;
	}

	init {
		server ?? {server = Server.default};
		list = List.new(30);
	}

	rec {arg clock, quant=0;
		if (isRecording.not) {
			if (clock.notNil) {
				clock.play({
					this.pr_startRec;
				}, quant);
			} {
				this.pr_startRec;
			};
			isRecording = true;
		}
	}

	stopRec {
		if (isRecording) {
			synth.release;
			isRecording = false;
		}
	}

	pr_startRec {
		var buf = Buffer.alloc(
			server,
			server.sampleRate * defBufLen,
			2,
			{arg bfr; synth = Synth('onset-buf-rec', ['buf', bfr, 'in', in, 'thr', 0.05])}
		);
		this.pr_addOSC;
		buffer = buf;
	}

	pr_addOSC {
		oscresp = OSCFunc({arg msg;
			if (msg[2] == 99) {
				list.add(msg[3]);
				msg.postln;
			}
		}, '\tr', server.addr);
	}

	pr_freeResources {
		synth.release;
		oscresp.free;
	}

	free {
		buffer.free;
		oscresp.free;
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
			SynthDef('simple-buf-rec', {arg buf, in=0, thr=0.05, gate=1;
				var env, sig, amp, trig, phase;

				env = EnvGen.ar(Env.asr(0.05), gate, doneAction:2); // global gate
				sig = SoundIn.ar([in, in+1]) * gate;

				phase = Phasor.ar(end:BufFrames.kr(buf));
				BufWr.ar(sig, buf, phase, 0);
			}).add;
		});
	}
}


// A player that plays, shuffles and bends one OnsetBuf
OnsetBufPlayer {

	var <obj; // todo: make nice setter for obj swap
	var <>teiler;  // 1/teiler=dur. Default 16th notes
	var <server;
	var <>numBars;
	var <>defaultQuant;
	var <durs;
	var <amps;
	var <globalAmp;
	var <decays;
	var <pos;
	var <proxy;
	var <pbinds; // one for simple one for grain playing. Can be swapped


	*new  {arg obj, type=\simple, teiler=4, numBars=1, defaultQuant=4, server;
		^super.newCopyArgs(obj, teiler, server, numBars, defaultQuant).init(type);
	}

	init { arg tp, mp;
		server ?? {server = Server.default};
		amps = PatternProxy(Pseq(1.0.dup(obj.list.size), inf));
		globalAmp = PatternProxy(1.0);
		durs = PatternProxy(1/teiler);
		pos = PatternProxy(Pseq(obj.list, inf));
		decays = PatternProxy(0.1);
		pbinds = (
			simple: Pbind (
				'instrument', 'simpleplayer',
				'dur', defaultQuant * numBars,
				'legato', 1,
				'amp', globalAmp,
				'buf', obj.buffer,
				'dec', decays // will do nothing with simpleplayer
			),
			grain: Pbind (
				'instrument', 'grainplayer',
				'dur', durs,
				'amp', amps * globalAmp,
				'buf', obj.buffer,
				'pos', pos,
				'dec', decays
			)
		);

		proxy = EventPatternProxy.new.quant_(defaultQuant);
		this.swap('simple');
	}

	// change the Pbind (todo: make safer)
	swap {arg type;
		proxy.source = pbinds.at(type);
	}

	play {arg clock, quant=0;
		clock = clock ?? {TempoClock.default};
		proxy.play(clock, quant:quant);
	}

	stop {
		proxy.stop;
	}

	// silence but keep rhythm?
	hush {
		globalAmp.source = 0.0;
	}

	on {
		globalAmp.source = 1.0;
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
		amps.quant = defaultQuant; // so the changes apply at next "bar"
		amps.source = Pseq(arr, inf); // set the source of PatternProxy
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

	// shuffles the pos array
	shuf {
		pos.source.list = pos.source.list.scramble;
	}

	dec {arg pat=0.1;
		decays.source = pat;
	}

	*initClass {
		ServerBoot.add({
			// Unexpected results, unless recorded portions are longer than dur
			SynthDef('simpleplayer', {arg buf, pos=0, gate=1, amp=1.0;
				var env, sig, phase;
				env = EnvGen.ar(Env.asr(0.01), gate, doneAction:2);
				phase = Phasor.ar(start:pos, end:BufFrames.kr(buf));
				sig = BufRd.ar(2, buf, phase, loop:0) * env * Lag.kr(amp);
				Out.ar(0, sig);
			}).add;

			// grain player with auto release
			SynthDef('grainplayer', {arg buf, pos=0, att=0.01, dec=0.3, amp=1.0;
				var env, sig, phase;
				env = EnvGen.ar(Env.perc(att, dec), 1, doneAction:2);
				phase = Phasor.ar(start:pos, end:BufFrames.kr(buf));
				sig = BufRd.ar(2, buf, phase, loop:0) * env * Lag.kr(amp);
				Out.ar(0, sig);
			}).add;
		});
	}
}