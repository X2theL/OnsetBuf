// A list of Buffers with corresponding arrays of onset points
OnsetBufList {
	var <server;
	var <>defBufLen;
	var <obl;
	var <oscresp;
	var <synth;
	var <isRecording = false;

	*new {arg server, defBufLen=10;
		^super.newCopyArgs(server, defBufLen).init;
	}

	init {
		server ?? {server = Server.default};
		obl = List.new;
	}

	rec {
		if (isRecording) {
			this.pr_freeResources;
			isRecording = false;
		}{
			this.pr_startRec;
			isRecording = true;
		}
	}

	// if no array is given one is created randomly
	add {arg buf, arr, num=4;
		if (arr.isNil) {
			arr = Array.fill(4, {
				rrand(0, buf.numFrames - server.sampleRate);
			});
		};
		obl.add([buf, arr‚]);
	}

	at {arg ind=0;
		^obl.clipAt(ind);
	}

	first {
		^obl[0]
	}

	last {
		^obl.last;
	}

	removeAt {

	}

	sizeAt {arg index=0;
		^obl[1].at(index).size;
	}

	clear {
		if (isRecording) {
			this.pr_freeResources;
		};
		obl.do {arg i;
			i[0].free;
		};
	}

	free {
		this.clear;
	}

	pr_freeResources {
		synth.release;
		oscresp.free;
	}

	//TODO: ADD AUDIO IN AND THR OPTIONS
	pr_startRec {
		var buf = Buffer.alloc(
			server,
			server.sampleRate * defBufLen,
			2,
			{arg bfr; synth = Synth('onset-buf-rec', ['buf', bfr, 'in', 0, 'thr', 0.05])}
		);
		obl.add([buf, Array.new(20)]);
		this.pr_addOSC;
	}

	// TODO: ADD ARGTEMPLATE TO MATCH ONLY ID 99
	pr_addOSC {
		var pointer = obl.indexOf(obl.last);
		oscresp = OSCFunc({arg msg;
			obl[pointer][1] = obl[pointer][1].add(msg[3]);
			msg.postln;
		}, '\tr', server.addr);
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
		});
	}
}

// A player that plays, shuffles and bends one item from an OnsetBufList
OnsetBufPlayer {
	var <>defaultQuant; // TODO: MAKE SETTER THAT APPLIES THIS TO ALL RELEVANT PATTERNPROXIES
	var <server;
	var <buffer;
	var <list;
	var <durs;
	var <>teiler=2;  // 1/teiler=dur
	var <player;
	var <proxy;

	*new  {arg defaultQuant=4, server;
		^super.newCopyArgs(defaultQuant, server).init;
	}

	init {
		server ?? {server = Server.default};
		proxy = PbindProxy ('instrument', 'grainplayer');
	}

	play {arg onsetbuf, amp=1.0, clock, quant;
		var durs, dec;

		durs = Array.fill(onsetbuf[1].size, { 1/teiler});
		clock = clock ?? {TempoClock.default};
		quant = quant ?? {defaultQuant}; // if no quant argument is provided default is used
		dec = (clock.tempo * teiler).reciprocal; // actual seconds. Beware of tempo changes!!
		proxy.set(
			'dur', Pseq(durs, inf),
			'dec', dec,
			'pos', Pseq(onsetbuf[1], inf),
			'buf', onsetbuf[0],
			'amp', amp
		);
		player = proxy.play(clock, quant:quant);
	}

	// Proxy stream goes on. Todo: make it start/stoppable at will (figure out how player works here)
	stop {
		player.stop;
	}

	// silence
	hush {
		proxy.set('amp', 0.0);
	}

	// make a rhythm
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
		proxy.at(\amp).quant = defaultQuant; // so the changes apply at next "bar"
		proxy.at(\amp).source = arr; // set the source of PatternProxy
	}

	// TODO: IMPROVE DOUBLE TRIGGER LOOP OR WHAT?
	*initClass {
		ServerBoot.add({
			// Unexpected results, unless recorded portions are longer than dur
			SynthDef('onsetplayer', {arg buf, pos=0, gate=1, amp=1.0;
				var env, sig, phase;
				env = EnvGen.ar(Env.asr(0.01), gate, doneAction:2);
				phase = Phasor.ar(start:pos, end:BufFrames.kr(buf));
				sig = BufRd.ar(2, buf, phase, loop:0) * env * amp;
				Out.ar(0, sig);
			}).add;

			// grain player with auto release
			SynthDef('grainplayer', {arg buf, pos=0, att=0.01, dec=0.3, amp=1.0;
				var env, sig, phase;
				env = EnvGen.ar(Env.perc(att, dec), 1, doneAction:2);
				phase = Phasor.ar(start:pos, end:BufFrames.kr(buf));
				sig = BufRd.ar(2, buf, phase, loop:0) * env * amp;
				Out.ar(0, sig);
			}).add;
		});
	}
}