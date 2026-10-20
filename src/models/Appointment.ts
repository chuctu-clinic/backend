import mongoose from 'mongoose';
import { ACTIVE_STATUSES, APPOINTMENT_STATUSES, SERVICE_TYPES, keysOf } from '../shared/constants.ts';

export { ACTIVE_STATUSES, APPOINTMENT_STATUSES, SERVICE_TYPES };

export const APPOINTMENT_SOURCES = ['ONLINE', 'COUNTER'] as const;
export type AppointmentSource = (typeof APPOINTMENT_SOURCES)[number];

const appointmentSchema = new mongoose.Schema(
  {
    ticketCode: { type: String, required: true, unique: true },
    userId: { type: mongoose.Schema.Types.ObjectId, ref: 'User', default: null },
    patientId: { type: mongoose.Schema.Types.ObjectId, ref: 'Patient', required: true },
    serviceType: { type: String, enum: keysOf(SERVICE_TYPES), required: true },
    slotDate: { type: Date, required: true },
    slotStart: { type: String, required: true },
    expectedTime: { type: Date, required: true },
    status: { type: String, enum: APPOINTMENT_STATUSES, default: 'BOOKED' },
    source: { type: String, enum: APPOINTMENT_SOURCES, required: true },
    createdBy: { type: mongoose.Schema.Types.ObjectId, ref: 'User', required: true },
    rescheduledFromId: { type: mongoose.Schema.Types.ObjectId, ref: 'Appointment', default: null },
    cancelReason: { type: String, default: null },
    cancelledBy: { type: mongoose.Schema.Types.ObjectId, ref: 'User', default: null },
    cancelledAt: { type: Date, default: null },
  },
  { timestamps: true },
);

appointmentSchema.index({ slotDate: 1, slotStart: 1, status: 1 });
appointmentSchema.index({ patientId: 1, slotDate: -1 });
appointmentSchema.index({ userId: 1, slotDate: -1 });

export const Appointment = mongoose.model('Appointment', appointmentSchema);
export type AppointmentDoc = ReturnType<(typeof Appointment)['hydrate']>;
